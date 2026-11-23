#!/usr/bin/env node
'use strict';

/**
 * Creates the Android emulator used by PDFr's manual and instrumented tests.
 *
 * Finds the Android SDK, installs the newest system image for the requested
 * tag and ABI, and creates (or recreates) a Pixel 5 AVD. The default name is
 * pdfr_full and the default RAM is 1536 MB. No API level is hard-coded: the
 * newest image installed or offered by sdkmanager is used, so the AVD follows
 * the current SDK automatically.
 *
 *     node tools\create-test-avd.js
 *     node tools\create-test-avd.js -Name pdfr_full -RamMb 1536
 *     tools\create-test-avd.bat pdfr_full 1536
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

function getLatestSystemImageApi(sdk, tag, abi, sdkManager) {
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
    if (sdkManager) {
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
        Name: { type: 'string', default: 'pdfr_full' },
        RamMb: { type: 'int', default: 1536 },
        Api: { type: 'string', default: '' },
        Abi: { type: 'string', default: 'x86_64' },
        Tag: { type: 'string', default: 'default' },
        Device: { type: 'string', default: 'pixel_5' },
    });

    // The .bat wrapper passes arguments straight through, so accept the old
    // positional form as well: create-test-avd.bat [name] [ram-mb].
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
    const { Api: requestedApi, Abi: abi, Tag: tag, Device: device } = options;

    const sdk = resolveSdk();
    step(`Android SDK: ${sdk}`);

    const sdkmanager = findCommandTool(sdk, 'sdkmanager.bat');
    const avdmanager = findCommandTool(sdk, 'avdmanager.bat');
    if (!sdkmanager || !avdmanager) {
        throw new Error('sdkmanager.bat/avdmanager.bat not found. Install Android command-line tools.');
    }

    let api = requestedApi;
    if (!api) {
        api = getLatestSystemImageApi(sdk, tag, abi, sdkmanager);
        if (!api) {
            throw new Error(`No Android ${tag} ${abi} system image is available. Install one with sdkmanager, or pass -Api.`);
        }
        step(`Newest ${tag} ${abi} image: android-${api}`);
    }

    const packageName = `system-images;android-${api};${tag};${abi}`;
    // sdkmanager.bat splits unquoted semicolons into separate arguments, so it
    // gets the slash form (the sdk-style path it documents anyway).
    const sdkPackage = packageName.replace(/;/g, '/');
    const imageDir = path.join(sdk, 'system-images', `android-${api}`, tag, abi);
    if (!fs.existsSync(imageDir)) {
        step(`Installing ${packageName} ...`);
        const installed = invokeNative(sdkmanager, [sdkPackage], { stream: true });
        if (installed.status !== 0 || !fs.existsSync(imageDir)) {
            throw new Error(`sdkmanager failed to install ${packageName}. Run '${sdkmanager} --licenses' and try again.`);
        }
    }

    const avdHome = process.env.ANDROID_AVD_HOME
        || path.join(process.env.USERPROFILE || os.homedir(), '.android', 'avd');
    const configPath = path.join(avdHome, `${name}.avd`, 'config.ini');

    step(`Creating AVD '${name}' (${device}, ${packageName}, ${ramMb} MB RAM)...`);
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

    // avdmanager does not expose a RAM flag, so update the generated config.
    const config = fs.readFileSync(configPath, 'utf8').split(/\r?\n/);
    if (config.length && config[config.length - 1] === '') config.pop();
    const kept = config.filter((line) => !/^hw\.ramSize=/.test(line) && !/^vm\.heapSize=/.test(line));
    kept.push(`hw.ramSize=${ramMb}`, 'vm.heapSize=256');
    fs.writeFileSync(configPath, `${kept.join('\r\n')}\r\n`, 'ascii');

    say('');
    step(`AVD '${name}' is ready.`);
    say(`       Start it with: tools\\start-emulator.bat ${name}`);
    say(`       Or set PDFR_AVD=${name} before running the other helpers.`);
    return 0;
});
