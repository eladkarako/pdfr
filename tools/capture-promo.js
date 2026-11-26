#!/usr/bin/env node
'use strict';

/**
 * Capture PDFr screenshots and a short promotional MP4 from a connected
 * emulator or device, then compose a poster.
 *
 * The script builds the debug APK first (Gradle and the emulator are both
 * memory-hungry), generates the fixtures, installs the app, and drives a
 * small tour of the reader:
 *
 *   01-empty.png       empty state with the disabled toolbar
 *   02-pdf-cover.png   the colourful Project Gutenberg sampler
 *   03-pdf-colour.png  a second, colour-heavy page
 *   04-overflow.png    the overflow menu with the mirrored toolbar entries
 *   05-info.png        the document-info editor
 *   06-epub.png        a reflowable EPUB
 *   pdfr-promo.mp4     scrolling and the overflow menu
 *   poster.png         3-4 screenshots composed into a shareable poster
 *
 * Use -SkipBuild when the APKs are already current. Use -StartEmulator to
 * start the configured AVD only after the build finishes.
 *
 *     node tools\capture-promo.js -StartEmulator
 *     node tools\capture-promo.js -SkipBuild -SkipVideo
 */

const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const {
    REPO_ROOT,
    ensureDir,
    fixturesDir,
    isFile,
    outDir,
    parseCli,
    resolveAdb,
    run,
    runBatch,
    runBatchTee,
    runMain,
    runStreaming,
    runTee,
    say,
    sleep,
    step,
    waitFor,
    warn,
} = require('./lib/common');

const TOOLS_DIR = __dirname;
const ROOT = REPO_ROOT;
const GRADLEW = path.join(ROOT, 'gradlew.bat');
const APP_ID = 'com.karako.pdfr';

let adb = null;
let options = null;
let fixtures = null;
let startedEmulator = false;
let targetDir = null;
let logFile = null;

function invokeAdb(args) {
    // adb writes progress such as "1 file pulled" to stderr, so success is
    // judged by the exit code and stderr is only shown when it fails.
    const result = run(adb, args, { allowFailure: true });
    if (result.status !== 0) {
        const detail = (result.stderr || result.stdout).trim();
        throw new Error(`adb failed (${result.status}): ${args.join(' ')}${detail ? `\n${detail}` : ''}`);
    }
    return result.stdout;
}

function getConnectedDevice() {
    const lines = invokeAdb(['devices']).split(/\r?\n/);
    for (const line of lines) {
        const match = line.match(/^(\S+)\s+device$/);
        if (match) return match[1];
    }
    return null;
}

async function startEmulatorIfNeeded() {
    if (getConnectedDevice()) return;

    if (!options.StartEmulator) {
        throw new Error('No device or emulator is connected. Re-run with -StartEmulator, or start tools\\start-emulator.bat first.');
    }

    step('Starting the emulator...');
    try {
        await runBatchTee(path.join(TOOLS_DIR, 'start-emulator.bat'), [], { logFile });
    } catch {
        throw new Error('The emulator did not start.');
    }
    startedEmulator = true;

    try {
        waitFor(() => getConnectedDevice(), {
            timeoutMs: 30000,
            intervalMs: 1000,
            label: 'an adb device',
        });
    } catch {
        throw new Error('The emulator started but adb still reports no device.');
    }
}

async function buildApk() {
    if (options.SkipBuild) {
        step('Skipping the Gradle build.');
        return;
    }

    step('Building the debug APK before touching the emulator...');
    // Gradle resolves the build from the working directory, so run the wrapper
    // from the repository root even when this script is started in tools\.
    try {
        await runBatchTee(GRADLEW, [':app:assembleDebug', '--console=plain'], {
            cwd: ROOT,
            logFile,
        });
    } catch {
        throw new Error(`Gradle build failed. See ${logFile}.`);
    }
}

async function newFixtures() {
    step(`Generating fixtures in ${fixtures}...`);
    try {
        await runTee(
            process.execPath,
            [path.join(TOOLS_DIR, 'make_test_files.js'), '--out', fixtures, '--no-gutenberg'],
            { cwd: ROOT, logFile },
        );
    } catch {
        throw new Error(`Fixture generation failed. See ${logFile}.`);
    }
    // Match the old script, which exported the folder for open-fixture.bat.
    process.env.PDFR_FIXTURES = fixtures;
}

function installDebugApk() {
    const abi = invokeAdb(['shell', 'getprop', 'ro.product.cpu.abi']).trim() || 'x86_64';

    const apk = path.join(ROOT, 'build', 'app', 'outputs', 'apk', 'debug', `app-${abi}-debug.apk`);
    if (!isFile(apk)) {
        throw new Error(`APK not found: ${apk}. Run without -SkipBuild.`);
    }

    step(`Installing ${abi} debug APK...`);
    const install = run(adb, ['install', '-r', '-t', apk], { allowFailure: true });
    if (install.status !== 0) {
        throw new Error('adb install failed.');
    }

    // Reset the stored theme and language so every run starts in the system
    // language with the system theme, and the first screenshot shows the empty
    // state rather than the document left open by the previous run.
    invokeAdb(['shell', 'pm', 'clear', APP_ID]);
}

function saveShot(name) {
    const remote = `/sdcard/pdfr-promo-${name}.png`;
    invokeAdb(['shell', `screencap -p ${remote}`]);
    invokeAdb(['pull', remote, path.join(targetDir, `${name}.png`)]);
    invokeAdb(['shell', 'rm', remote]);
    say(`  saved ${name}.png`);
}

function decodeXml(value) {
    return value
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&amp;/g, '&');
}

/** Returns the uiautomator nodes of the active window, or null. */
function getUiDocument() {
    const remote = '/sdcard/pdfr-promo-ui.xml';
    const local = path.join(targetDir, 'window.xml');
    try {
        // Dump only the active window. uiautomator would otherwise include the
        // window behind an open menu, where the toolbar button carries the same
        // label as the menu entry; the first match would then tap the toolbar
        // and close the menu instead of choosing the entry.
        invokeAdb(['shell', `uiautomator dump ${remote}`]);
        invokeAdb(['pull', remote, local]);
    } catch {
        return null;
    } finally {
        try { invokeAdb(['shell', 'rm', remote]); } catch { /* already gone */ }
    }
    if (!isFile(local)) return null;

    try {
        const xml = fs.readFileSync(local, 'utf8');
        const nodes = [];
        for (const tag of xml.match(/<node\b[^>]*>/g) || []) {
            const attributes = {};
            for (const match of tag.matchAll(/([\w:-]+)="([^"]*)"/g)) {
                attributes[match[1]] = decodeXml(match[2]);
            }
            nodes.push(attributes);
        }
        return nodes;
    } catch {
        return null;
    }
}

function findNodeCenter(label) {
    const nodes = getUiDocument();
    if (!nodes) return null;

    const node = nodes.find((candidate) => (
        candidate.text === label || candidate['content-desc'] === label
    ));
    if (!node) return null;

    const match = String(node.bounds || '').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (!match) return null;

    return {
        x: Math.round((Number(match[1]) + Number(match[3])) / 2),
        y: Math.round((Number(match[2]) + Number(match[4])) / 2),
    };
}

function waitNodeCenter(label, attempts = 10) {
    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        const center = findNodeCenter(label);
        if (center) return center;
        sleep(600);
    }
    return null;
}

function tapNode(label) {
    const center = waitNodeCenter(label);
    if (!center) {
        throw new Error(`Could not find '${label}' in the UI hierarchy.`);
    }
    invokeAdb(['shell', 'input', 'tap', String(center.x), String(center.y)]);
}

function getScreenSize() {
    const output = invokeAdb(['shell', 'wm', 'size']);
    const matches = [...output.matchAll(/(\d+)x(\d+)/g)];
    if (matches.length === 0) return { width: 1080, height: 2340 };
    const last = matches[matches.length - 1];
    return { width: Number(last[1]), height: Number(last[2]) };
}

function swipe(startFraction, endFraction) {
    const size = getScreenSize();
    const middle = Math.trunc(size.width / 2);
    const start = Math.trunc(size.height * startFraction / 100);
    const end = Math.trunc(size.height * endFraction / 100);
    invokeAdb(['shell', 'input', 'swipe', String(middle), String(start), String(middle), String(end), '350']);
}

function swipeUp(fractionStart = 75, fractionEnd = 30) {
    swipe(fractionStart, fractionEnd);
}

function swipeDown(fractionStart = 30, fractionEnd = 75) {
    swipe(fractionStart, fractionEnd);
}

function openFixture(name) {
    const result = runBatch(path.join(TOOLS_DIR, 'open-fixture.bat'), [name], {
        env: { PDFR_FIXTURES: fixtures },
        allowFailure: true,
    });
    if (result.status !== 0) {
        throw new Error(`Could not open fixture ${name}.`);
    }
}

function restartApp() {
    invokeAdb(['shell', 'am', 'force-stop', APP_ID]);
    invokeAdb(['shell', 'am', 'start', '-n', `${APP_ID}/.MainActivity`]);
    sleep(2000);
}

function captureScreenshots() {
    step('Capturing screenshots...');

    restartApp();
    sleep(1000);
    saveShot('01-empty');

    openFixture('gutenberg\\gutenberg-color.pdf');
    sleep(5000);
    saveShot('02-pdf-cover');

    swipeUp(76, 32);
    sleep(2000);
    saveShot('03-pdf-colour');

    tapNode('More options');
    sleep(1000);
    saveShot('04-overflow');

    tapNode('Document info');
    sleep(2000);
    saveShot('05-info');
    invokeAdb(['shell', 'input', 'keyevent', '4']);
    sleep(1000);

    openFixture('alice.epub');
    sleep(4000);
    saveShot('06-epub');
}

async function captureVideo() {
    if (options.SkipVideo) {
        step('Skipping the MP4 capture.');
        return;
    }

    step('Recording a short MP4...');
    restartApp();
    openFixture('gutenberg\\gutenberg-color.pdf');
    sleep(4000);

    const menu = waitNodeCenter('More options');
    if (!menu) {
        throw new Error('Could not find the overflow button for the video.');
    }

    const remote = '/sdcard/pdfr-promo.mp4';
    try { invokeAdb(['shell', 'rm', remote]); } catch { /* nothing to remove */ }

    // Portrait phones exceed screenrecord's encoder limits at full resolution,
    // so scale to 720 px wide while keeping the display aspect ratio.
    const screen = getScreenSize();
    const videoWidth = 720;
    const videoHeight = Math.round(screen.height * videoWidth / screen.width / 2) * 2;
    const recorder = spawn(adb, [
        'shell', 'screenrecord',
        '--bit-rate', '8000000',
        '--time-limit', '30',
        '--size', `${videoWidth}x${videoHeight}`,
        remote,
    ], { windowsHide: true, stdio: 'ignore' });

    sleep(1000);
    swipeUp(76, 34);
    sleep(2000);
    swipeUp(76, 34);
    sleep(2000);
    invokeAdb(['shell', 'input', 'tap', String(menu.x), String(menu.y)]);
    sleep(2000);
    invokeAdb(['shell', 'input', 'keyevent', '4']);
    sleep(1000);
    swipeDown(34, 76);
    sleep(2000);

    try { invokeAdb(['shell', 'pkill', '-INT', 'screenrecord']); } catch { /* already stopped */ }
    await new Promise((resolve) => {
        if (recorder.exitCode !== null || recorder.signalCode !== null) {
            resolve();
            return;
        }
        const timer = setTimeout(resolve, 15000);
        recorder.once('close', () => {
            clearTimeout(timer);
            resolve();
        });
    });

    invokeAdb(['pull', remote, path.join(targetDir, 'pdfr-promo.mp4')]);
    invokeAdb(['shell', 'rm', remote]);
    say('  saved pdfr-promo.mp4');
}

function writeReadme() {
    const lines = [
        'PDFr promo capture',
        '==================',
        '',
        'Generated by tools\\capture-promo.js.',
        '',
        'Screenshots:',
        '  01-empty.png       empty state, disabled document actions',
        '  02-pdf-cover.png   colourful Project Gutenberg sampler',
        '  03-pdf-colour.png  another colour-heavy PDF page',
        '  04-overflow.png    mirrored toolbar entries in the overflow menu',
        '  05-info.png        document-info editor',
        '  06-epub.png        reflowable EPUB',
        '',
        'Video:',
        '  pdfr-promo.mp4     scrolling and the overflow menu',
        '',
        'Poster:',
        '  poster.png         3-4 screenshots composed for sharing',
        '',
        'Log:',
        '  capture.log        streamed Gradle, fixture and emulator output',
        '',
        'The colour PDF is generated by tools\\make_test_files.js from',
        "public-domain Project Gutenberg excerpts. See the fixture folder's",
        'SOURCES.txt for the source eBooks.',
        '',
        'Regenerate everything:',
        '  tools\\capture-promo.js -StartEmulator',
        '',
    ];
    fs.writeFileSync(path.join(targetDir, 'README.txt'), `${lines.join('\n')}\n`, 'utf8');
}

async function main(argv) {
    options = parseCli(argv, {
        OutDir: { type: 'string', default: outDir('promo') },
        StartEmulator: { type: 'bool', default: false },
        SkipBuild: { type: 'bool', default: false },
        SkipVideo: { type: 'bool', default: false },
        KeepEmulator: { type: 'bool', default: false },
    });

    fixtures = fixturesDir();
    targetDir = path.resolve(options.OutDir);
    adb = resolveAdb();

    ensureDir(targetDir);
    logFile = path.join(targetDir, 'capture.log');

    await buildApk();
    await startEmulatorIfNeeded();
    await newFixtures();
    installDebugApk();

    invokeAdb(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']);
    invokeAdb(['shell', 'wm', 'dismiss-keyguard']);

    captureScreenshots();
    await captureVideo();

    step('Composing the poster...');
    let posterOk = false;
    try {
        const poster = await runStreaming(
            process.execPath,
            [path.join(TOOLS_DIR, 'make-poster.js'), '-OutDir', targetDir],
            { cwd: ROOT, allowFailure: true },
        );
        posterOk = poster.status === 0;
    } catch { /* reported below */ }
    if (!posterOk) {
        warn('The poster step failed; the screenshots and video are still usable.');
    }

    writeReadme();

    if (startedEmulator && !options.KeepEmulator) {
        step('Stopping the emulator...');
        try {
            await runBatchTee(path.join(TOOLS_DIR, 'stop-emulator.bat'), [], { logFile });
        } catch { /* stop-emulator.bat already explained the failure */ }
    }

    say('');
    say(`[OK] Promo assets are in ${targetDir}`);

    // adb writes progress to stderr, which the old PowerShell script counted
    // as an error even when the command succeeded. Failures above throw, so
    // reaching this point is success.
    return 0;
}

runMain(() => main(process.argv.slice(2)));
