#!/usr/bin/env node
'use strict';

/**
 * Creates the Android TV emulator used by PDFr's TV checks.
 *
 * Finds the Android SDK, installs the newest TV system image for the requested
 * ABI when it is missing, and creates (or recreates) a 1080p TV AVD. The default
 * name is pdfr_tv. No API level is hard-coded: the newest image installed or
 * offered by sdkmanager is used, so a tag that only ships for some ABI/API
 * combinations (such as Android TV) still gets a valid image.
 *
 * A TV AVD has no touchscreen, so the app is driven with a D-pad: the arrow
 * keys move focus and KEYCODE_DPAD_CENTER activates the focused button. The
 * generated config enables the D-pad and a keyboard, which is what the
 * accessibility tests use when they Tab through the toolbar.
 *
 * The image is large (about 1.5 GB), so the first run downloads it. Use
 * -SkipInstall to fail instead of downloading when the image is missing.
 *
 *     node tools\create-tv-avd.js
 *     node tools\create-tv-avd.js -Name pdfr_tv -RamMb 2048
 *     tools\create-tv-avd.bat pdfr_tv 2048
 *
 * Uses only Node.js built-ins (plus tools/lib/common.js).
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const {
    REPO_ROOT,
    compareVersionsDesc,
    parseCli,
    resolveSdk,
    runMain,
    say,
    step,
} = require('./lib/common');

function escapeRegex(text) {
    return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function walkForFile(directory, fileName, found) {
    let entries;
    try {
        entries = fs.readdirSync(directory, { withFileTypes: true });
    } catch {
        return;
    }
    for (const entry of entries) {
        const full = path.join(directory, entry.name);
        if (entry.isDirectory()) walkForFile(full, fileName, found);
        else if (entry.isFile() && entry.name.toLowerCase() === fileName.toLowerCase()) found.push(full);
    }
}

/** Finds sdkmanager.bat/avdmanager.bat under cmdline-tools or tools. */
function findCommandTool(sdk, fileName) {
    for (const root of [path.join(sdk, 'cmdline-tools'), path.join(sdk, 'tools')]) {
        if (!fs.existsSync(root)) continue;
        const found = [];
        walkForFile(root, fileName, found);
        if (found.length) {
            found.sort((a, b) => b.localeCompare(a));
            return found[0];
        }
    }
    return null;
}

/**
 * Runs sdkmanager/avdmanager and returns { status, output }.
 *
 * Streaming mode inherits stdout (so downloads show progress) and captures
 * stderr, which is printed only on failure: both tools write warnings and
 * progress there, and that text is not an error.
 */
function invokeNative(filePath, args = [], options = {}) {
    const isBatch = /\.(bat|cmd)$/i.test(filePath);
    const command = isBatch ? (process.env.COMSPEC || 'cmd.exe') : filePath;
    const argv = isBatch ? ['/d', '/c', filePath, ...args] : args;
    const result = spawnSync(command, argv, {
        cwd: REPO_ROOT,
        input: options.input,
        stdio: options.stream ? ['pipe', 'inherit', 'pipe'] : ['pipe', 'pipe', 'pipe'],
        encoding: 'utf8',
        windowsHide: true,
        maxBuffer: 64 * 1024 * 1024,
    });
    if (result.error) throw result.error;

    const status = result.status ?? (result.signal ? 1 : 0);
    if (options.stream) {
        if (status !== 0 && result.stderr) {
            process.stdout.write(result.stderr.endsWith('\n') ? result.stderr : `${result.stderr}\n`);
        }
        return { status, output: '' };
    }
    return { status, output: `${result.stdout || ''}${result.stderr || ''}` };
}

function apiVersion(api) {
    return api.replace(/[^0-9.].*$/, '') || '0.0';
}

function getLatestSystemImageApi(sdk, tag, abi, sdkManager, installedOnly) {
    const apis = [];

    // Installed images are the fastest source and work offline.
    const root = path.join(sdk, 'system-images');
    if (fs.existsSync(root)) {
        let entries = [];
        try {
            entries = fs.readdirSync(root, { withFileTypes: true });
        } catch {
            // Ignore an unreadable SDK folder, like the PowerShell -ErrorAction.
        }
        for (const entry of entries) {
            if (!entry.isDirectory()) continue;
            const api = entry.name.replace(/^android-/, '');
            if (fs.existsSync(path.join(root, entry.name, tag, abi))) apis.push(api);
        }
    }

    // Ask sdkmanager for images that can be installed. The tool names are
    // stable across SDK versions, so this follows the newest release without
    // hard-coding an API level.
    if (!installedOnly && sdkManager) {
        const list = invokeNative(sdkManager, ['--list']).output
            .replace(/\u001b\[[0-9;]*m/g, '');
        const pattern = new RegExp(
            `system-images[;/]android-([^;/ ]+)[;/]${escapeRegex(tag)}[;/]${escapeRegex(abi)}(?:\\s|$)`,
        );
        for (const line of list.split(/\r?\n/)) {
            const match = pattern.exec(line);
            if (match) apis.push(match[1]);
        }
    }

    if (apis.length === 0) return null;
    const unique = [...new Set(apis)];
    unique.sort((a, b) => compareVersionsDesc(apiVersion(a), apiVersion(b)));
    return unique[0];
}

runMain(async () => {
    const options = parseCli(process.argv.slice(2), {
        Name: { type: 'string', default: 'pdfr_tv' },
        RamMb: { type: 'int', default: 2048 },
        Api: { type: 'string', default: '' },
        Abi: { type: 'string', default: 'x86_64' },
        Tag: { type: 'string', default: 'android-tv' },
        Device: { type: 'string', default: 'tv_1080p' },
        SkipInstall: { type: 'bool', default: false },
    });

    // The .bat wrapper maps its first two arguments to -Name/-RamMb, but accept
    // the positional form for direct `node tools\create-tv-avd.js` use too.
    let name = options.Name;
    let ramMb = options.RamMb;
    const positional = options._positional;
    if (positional[0] !== undefined) name = positional[0];
    if (positional[1] !== undefined) {
        const parsed = Number.parseInt(positional[1], 10);
        if (Number.isNaN(parsed)) {
            throw new Error(`Option -RamMb needs a whole number, got '${positional[1]}'.`);
        }
        ramMb = parsed;
    }
    const { Api: requestedApi, Abi: abi, Tag: tag, Device: device, SkipInstall: skipInstall } = options;

    const sdk = resolveSdk();
    step(`Android SDK: ${sdk}`);

    const sdkmanager = findCommandTool(sdk, 'sdkmanager.bat');
    const avdmanager = findCommandTool(sdk, 'avdmanager.bat');
    if (!sdkmanager || !avdmanager) {
        throw new Error('sdkmanager.bat/avdmanager.bat not found. Install Android command-line tools.');
    }

    let api = requestedApi;
    if (!api) {
        api = getLatestSystemImageApi(sdk, tag, abi, sdkmanager, skipInstall);
        if (!api) {
            throw new Error(`No installed Android ${tag} ${abi} system image was found. Run without -SkipInstall, or pass -Api.`);
        }
        step(`Newest ${tag} ${abi} image: android-${api}`);
    }

    const packageName = `system-images;android-${api};${tag};${abi}`;
    // sdkmanager.bat splits unquoted semicolons into separate arguments, so it
    // gets the slash form (the sdk-style path it documents anyway).
    const sdkPackage = packageName.replace(/;/g, '/');
    const imageDir = path.join(sdk, 'system-images', `android-${api}`, tag, abi);
    if (!fs.existsSync(imageDir)) {
        if (skipInstall) {
            throw new Error(`${packageName} is not installed. Run without -SkipInstall, or install it with sdkmanager.`);
        }
        step(`Installing ${packageName} (large download)...`);
        const installed = invokeNative(sdkmanager, [sdkPackage], { stream: true });
        if (installed.status !== 0 || !fs.existsSync(imageDir)) {
            throw new Error(`sdkmanager failed to install ${packageName}. Run '${sdkmanager} --licenses' and try again.`);
        }
    }

    const avdHome = process.env.ANDROID_AVD_HOME
        || path.join(process.env.USERPROFILE || os.homedir(), '.android', 'avd');
    const configPath = path.join(avdHome, `${name}.avd`, 'config.ini');

    step(`Creating TV AVD '${name}' (${device}, ${packageName}, ${ramMb} MB RAM)...`);
    // 'no' declines the optional custom-hardware-profile prompt.
    const created = invokeNative(
        avdmanager,
        ['create', 'avd', '--force', '--name', name, '--package', packageName, '--device', device, '--sdcard', '512M'],
        { input: 'no\n', stream: true },
    );
    if (created.status !== 0) {
        throw new Error(`avdmanager failed to create '${name}'.`);
    }

    if (!fs.existsSync(configPath)) {
        throw new Error(`AVD config was not created at ${configPath}`);
    }

    // avdmanager does not expose RAM or input flags, so update the generated
    // config. The D-pad is the TV remote's only pointer; without it the toolbar
    // cannot be focused at all. The keyboard makes the Tab-based accessibility
    // checks work on the same AVD.
    const config = fs.readFileSync(configPath, 'utf8').split(/\r?\n/);
    if (config.length && config[config.length - 1] === '') config.pop();
    const kept = config.filter((line) => !/^hw\.ramSize=/.test(line)
        && !/^vm\.heapSize=/.test(line)
        && !/^hw\.dPad=/.test(line)
        && !/^hw\.keyboard=/.test(line));
    kept.push(`hw.ramSize=${ramMb}`, 'vm.heapSize=256', 'hw.dPad=yes', 'hw.keyboard=yes');
    fs.writeFileSync(configPath, `${kept.join('\r\n')}\r\n`, 'ascii');

    say('');
    step(`TV AVD '${name}' is ready.`);
    say(`       Start it with: tools\\start-emulator.bat ${name}`);
    say('       Run the TV checks with: tools\\run-instrumented-tests.bat com.karako.pdfr.TvSupportInstrumentedTest');
    say(`       Or set PDFR_AVD=${name} before running the other helpers.`);
    return 0;
});
