#!/usr/bin/env node
'use strict';

/**
 * Builds the static qpdf JNI bridge for Android and refreshes app/src/main/jniLibs.
 *
 * Downloads qpdf and libjpeg-turbo, builds both as static libraries for each
 * requested Android ABI with the NDK, links app/src/main/cpp/qpdf_jni.cpp,
 * strips the result and copies it to app/src/main/jniLibs/<abi>/.
 *
 * The checked-in libraries are enough for a normal Gradle build. Run this
 * script only after changing qpdf_jni.cpp, upgrading qpdf/libjpeg-turbo, or
 * adding an ABI.
 *
 * Requirements: Android SDK with NDK 30+, CMake 3.22+ (the SDK copy is fine),
 * curl and tar (both ship with Windows 10/11).
 *
 *     node tools\build-qpdf-android.js
 *     node tools\build-qpdf-android.js -Abis arm64-v8a,x86_64 -Clean
 *     tools\build-qpdf-android.bat -Abis arm64-v8a,x86_64 -Clean
 *
 * Uses only Node.js built-ins (plus tools/lib/common.js).
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
    REPO_ROOT,
    ensureDir,
    formatBytes,
    isWindows,
    parseCli,
    resolveSdk,
    run,
    runMain,
    runStreaming,
    say,
    step,
    which,
} = require('./lib/common');

function writeStep(text) {
    say('');
    step(text);
}

function findSdk(requested) {
    if (requested) {
        if (!fs.existsSync(requested)) throw new Error('Android SDK not found. Set ANDROID_SDK_ROOT.');
        return path.resolve(requested);
    }
    try {
        return resolveSdk();
    } catch {
        throw new Error('Android SDK not found. Set ANDROID_SDK_ROOT.');
    }
}

function findNdk(sdk, requested) {
    const hasToolchain = (directory) => fs.existsSync(path.join(directory, 'build', 'cmake', 'android.toolchain.cmake'));
    if (requested && hasToolchain(requested)) return path.resolve(requested);

    for (const root of [path.join(sdk, 'ndk'), path.join(sdk, 'ndk-bundle')]) {
        if (!fs.existsSync(root)) continue;
        const candidate = fs.readdirSync(root, { withFileTypes: true })
            .filter((entry) => entry.isDirectory())
            .map((entry) => path.join(root, entry.name))
            .filter(hasToolchain)
            .sort((a, b) => path.basename(b).localeCompare(path.basename(a)))[0];
        if (candidate) return candidate;
    }
    throw new Error('Android NDK not found. Set ANDROID_NDK_ROOT.');
}

function findCMake(sdk) {
    const root = path.join(sdk, 'cmake');
    if (fs.existsSync(root)) {
        const newest = fs.readdirSync(root, { withFileTypes: true })
            .filter((entry) => entry.isDirectory())
            .map((entry) => entry.name)
            .sort((a, b) => b.localeCompare(a))[0];
        if (newest) {
            const bin = path.join(root, newest, 'bin');
            const exe = path.join(bin, 'cmake.exe');
            if (fs.existsSync(exe)) {
                // Prefer the SDK's CMake, and make its matching ninja visible.
                process.env.PATH = `${bin}${path.delimiter}${process.env.PATH || ''}`;
                return exe;
            }
        }
    }
    const onPath = which('cmake');
    if (onPath) return onPath;
    throw new Error('CMake not found. Install it from the SDK Manager or put it on PATH.');
}

function getNdkTool(ndk, name) {
    const hostTag = isWindows() ? 'windows-x86_64' : 'linux-x86_64';
    const tool = path.join(ndk, 'toolchains', 'llvm', 'prebuilt', hostTag, 'bin', name);
    if (!fs.existsSync(tool)) throw new Error(`NDK tool not found: ${tool}`);
    return tool;
}

function download(url, destination, skipDownload) {
    if (skipDownload) {
        throw new Error(`Missing ${path.basename(destination)}; run without -SkipDownload to download it.`);
    }
    step(`Downloading ${url}`);
    const curl = isWindows() ? 'curl.exe' : 'curl';
    const result = run(curl, ['-fsSL', url, '-o', destination], { allowFailure: true });
    if (result.status !== 0) throw new Error(`download failed: ${url}`);
}

function prepareSources(work, options) {
    const src = path.join(work, 'src');
    ensureDir(src);

    const qpdfDir = path.join(src, `qpdf-${options.qpdfVersion}`);
    if (!fs.existsSync(qpdfDir)) {
        const archive = path.join(src, `qpdf-${options.qpdfVersion}.tar.gz`);
        if (!fs.existsSync(archive)) {
            download(
                `https://github.com/qpdf/qpdf/releases/download/v${options.qpdfVersion}/qpdf-${options.qpdfVersion}.tar.gz`,
                archive,
                options.skipDownload,
            );
        }
        const result = run('tar', ['-xzf', archive, '-C', src], { allowFailure: true });
        if (result.status !== 0) throw new Error('could not extract qpdf');
    }

    const jpegDir = path.join(src, `libjpeg-turbo-${options.jpegVersion}`);
    if (!fs.existsSync(jpegDir)) {
        const archive = path.join(src, `libjpeg-turbo-${options.jpegVersion}.tar.gz`);
        if (!fs.existsSync(archive)) {
            download(
                `https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/${options.jpegVersion}/libjpeg-turbo-${options.jpegVersion}.tar.gz`,
                archive,
                options.skipDownload,
            );
        }
        const result = run('tar', ['-xzf', archive, '-C', src], { allowFailure: true });
        if (result.status !== 0) throw new Error('could not extract libjpeg-turbo');
    }

    return { qpdf: qpdfDir, jpeg: jpegDir };
}

/** Streams a command's output and throws `failureMessage` when it fails. */
async function runChecked(file, args, failureMessage) {
    const { status } = await runStreaming(file, args, { allowFailure: true });
    if (status !== 0) throw new Error(failureMessage);
}

async function invokeBuild(cmake, args, what) {
    step(what);
    await runChecked(cmake, args, what);
}

runMain(async () => {
    const options = parseCli(process.argv.slice(2), {
        Abis: { type: 'list', default: ['armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'] },
        QpdfVersion: { type: 'string', default: '12.4.2' },
        JpegVersion: { type: 'string', default: '3.2.1' },
        AndroidPlatform: { type: 'string', default: 'android-24' },
        Ndk: { type: 'string', default: process.env.ANDROID_NDK_ROOT || '' },
        Sdk: { type: 'string', default: process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || '' },
        Clean: { type: 'bool', default: false },
        SkipDownload: { type: 'bool', default: false },
    });

    // parseCli already splits `-Abis a,b,c` into separate entries.
    const abis = options.Abis;
    const repo = REPO_ROOT;
    const work = path.join(repo, 'build', 'qpdf');
    const jniLibs = path.join(repo, 'app', 'src', 'main', 'jniLibs');
    const cpp = path.join(repo, 'app', 'src', 'main', 'cpp');
    const jobs = Math.max(1, os.cpus().length);

    const sdk = findSdk(options.Sdk);
    const ndk = findNdk(sdk, options.Ndk);
    const cmake = findCMake(sdk);
    const strip = getNdkTool(ndk, 'llvm-strip.exe');
    const sources = prepareSources(work, {
        qpdfVersion: options.QpdfVersion,
        jpegVersion: options.JpegVersion,
        skipDownload: options.SkipDownload,
    });

    const common = [
        '-G', 'Ninja',
        `-DCMAKE_TOOLCHAIN_FILE=${path.join(ndk, 'build', 'cmake', 'android.toolchain.cmake')}`,
        `-DANDROID_PLATFORM=${options.AndroidPlatform}`,
        '-DCMAKE_BUILD_TYPE=Release',
    ];

    step(`Repo:     ${repo}`);
    step(`NDK:      ${ndk}`);
    step(`CMake:    ${cmake}`);
    step(`qpdf:     ${sources.qpdf}`);
    step(`jpegturbo:${sources.jpeg}`);
    step(`ABIs:     ${abis.join(', ')}`);

    for (const abi of abis) {
        writeStep(`Building ${abi}`);

        const prefix = path.join(work, `prefix-${abi}`);
        const jpegBuild = path.join(work, `jpeg-${abi}`);
        const qpdfBuild = path.join(work, `qpdf-${abi}`);
        const jniBuild = path.join(work, `jni-${abi}`);

        if (options.Clean) {
            for (const directory of [prefix, jpegBuild, qpdfBuild, jniBuild]) {
                try {
                    fs.rmSync(directory, { recursive: true, force: true });
                } catch {
                    // Remove-Item -ErrorAction SilentlyContinue behaved the same.
                }
            }
        }

        // --- libjpeg-turbo (static, no SIMD / tools / tests) ---------------
        ensureDir(jpegBuild);
        await invokeBuild(cmake, [
            '-S', sources.jpeg,
            '-B', jpegBuild,
            ...common,
            `-DANDROID_ABI=${abi}`,
            '-DENABLE_SHARED=OFF',
            '-DENABLE_STATIC=ON',
            '-DWITH_TURBOJPEG=OFF',
            '-DWITH_SIMD=OFF',
            '-DWITH_TOOLS=OFF',
            '-DWITH_TESTS=OFF',
            `-DCMAKE_INSTALL_PREFIX=${prefix}`,
        ], `configure libjpeg-turbo (${abi})`);

        await runChecked(
            cmake,
            ['--build', jpegBuild, '--target', 'install', '-j', String(jobs)],
            `build libjpeg-turbo (${abi})`,
        );

        const jpegLib = path.join(prefix, 'lib', 'libjpeg.a');
        if (!fs.existsSync(jpegLib)) throw new Error(`libjpeg.a was not installed for ${abi}`);

        // --- qpdf (static, native crypto, static jpeg) ---------------------
        ensureDir(qpdfBuild);
        await invokeBuild(cmake, [
            '-S', sources.qpdf,
            '-B', qpdfBuild,
            ...common,
            `-DANDROID_ABI=${abi}`,
            '-DBUILD_SHARED_LIBS=OFF',
            '-DBUILD_STATIC_LIBS=ON',
            '-DUSE_IMPLICIT_CRYPTO=OFF',
            '-DREQUIRE_CRYPTO_NATIVE=ON',
            '-DDEFAULT_CRYPTO=native',
            '-DSTATIC_JPEG=ON',
            '-DBUILD_DOC=OFF',
            '-DBUILD_TESTING=OFF',
            '-DINSTALL_EXAMPLES=OFF',
            '-DINSTALL_MANUAL=OFF',
            '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
            // qpdf forces non-PIC for static builds. The JNI shared library
            // needs PIC objects, so force the flag through the global compiler
            // flags.
            '-DCMAKE_C_FLAGS=-fPIC',
            '-DCMAKE_CXX_FLAGS=-fPIC',
            `-DLIBJPEG_H_PATH=${path.join(prefix, 'include')}`,
            `-DLIBJPEG_LIB_PATH=${jpegLib}`,
            '-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH',
            '-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH',
            `-DCMAKE_INSTALL_PREFIX=${prefix}`,
        ], `configure qpdf (${abi})`);

        await runChecked(
            cmake,
            ['--build', qpdfBuild, '--target', 'libqpdf', '-j', String(jobs)],
            `build libqpdf (${abi})`,
        );

        const qpdfLib = path.join(qpdfBuild, 'libqpdf', 'libqpdf.a');
        if (!fs.existsSync(qpdfLib)) throw new Error(`libqpdf.a was not built for ${abi}`);

        // --- JNI bridge ----------------------------------------------------
        ensureDir(jniBuild);
        await invokeBuild(cmake, [
            '-S', cpp,
            '-B', jniBuild,
            ...common,
            `-DANDROID_ABI=${abi}`,
            '-DANDROID_STL=c++_static',
            `-DQPDF_SOURCE_DIR=${sources.qpdf}`,
            `-DQPDF_BUILD_DIR=${qpdfBuild}`,
            `-DQPDF_LIB=${qpdfLib}`,
            `-DJPEG_LIB=${jpegLib}`,
        ], `configure qpdf JNI (${abi})`);

        await runChecked(
            cmake,
            ['--build', jniBuild, '-j', String(jobs)],
            `link libqpdfjni.so (${abi})`,
        );

        const built = path.join(jniBuild, 'libqpdfjni.so');
        if (!fs.existsSync(built)) throw new Error(`libqpdfjni.so was not built for ${abi}`);

        const destinationDir = path.join(jniLibs, abi);
        ensureDir(destinationDir);
        const destination = path.join(destinationDir, 'libqpdfjni.so');
        fs.copyFileSync(built, destination);

        await runChecked(strip, ['--strip-unneeded', destination], `strip ${abi}`);

        const size = fs.statSync(destination).size;
        say(`[PDFr] ${abi.padEnd(12)} libqpdfjni.so ${formatBytes(size).padStart(10)} bytes`);
        if (size < 1024 * 1024) {
            say('[PDFr] WARNING: the stripped library looks unexpectedly small.');
        }
    }

    writeStep('Done');
    say(`[PDFr] Updated ${jniLibs}`);
    say('[PDFr] Rebuild the APK with: gradlew :app:assembleDebug');
    return 0;
});
