'use strict';

/**
 * Shared helpers for the PDFr tools.
 *
 * These scripts are Windows-first, like the batch files that launch them, but
 * they only use Node.js built-ins: no npm packages. Command-line parsing aims
 * to accept the same switches the old PowerShell scripts used, so existing
 * `.bat` wrappers and muscle memory keep working:
 *
 *     node tools\gestures.js -Gesture pinch-out -ScreenWidth 1080
 *     node tools\reader-checks.js -Section text,selection
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');

const REPO_ROOT = path.resolve(__dirname, '..', '..');

/** Newest-first semver-ish comparison for directory names. */
function compareVersionsDesc(a, b) {
    const split = (value) => value.split(/[.\-+]/).map((part) => {
        const number = Number.parseInt(part, 10);
        return Number.isNaN(number) ? part : number;
    });
    const left = split(a);
    const right = split(b);
    const length = Math.max(left.length, right.length);
    for (let index = 0; index < length; index += 1) {
        const x = left[index] ?? 0;
        const y = right[index] ?? 0;
        if (x === y) continue;
        if (typeof x === 'number' && typeof y === 'number') return y - x;
        return String(y).localeCompare(String(x));
    }
    return 0;
}

function isWindows() {
    return process.platform === 'win32';
}

function localStorage() {
    return process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
}

/** Finds the Android SDK without requiring anything beyond the usual env vars. */
function resolveSdk() {
    const candidates = [process.env.ANDROID_SDK_ROOT, process.env.ANDROID_HOME];
    candidates.push(path.join(localStorage(), 'Android', 'Sdk'));
    for (const candidate of candidates) {
        if (candidate && fs.existsSync(candidate)) return path.resolve(candidate);
    }
    throw new Error('Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME.');
}

function resolveAdb() {
    if (process.env.ADB && fs.existsSync(process.env.ADB)) return process.env.ADB;
    const sdk = resolveSdk();
    const adb = path.join(sdk, 'platform-tools', 'adb.exe');
    if (!fs.existsSync(adb)) {
        throw new Error('adb was not found. Set ANDROID_SDK_ROOT or ADB.');
    }
    return adb;
}

function resolveEmulator() {
    if (process.env.EMULATOR && fs.existsSync(process.env.EMULATOR)) return process.env.EMULATOR;
    const sdk = resolveSdk();
    const emulator = path.join(sdk, 'emulator', 'emulator.exe');
    if (!fs.existsSync(emulator)) {
        throw new Error('The Android emulator was not found. Set ANDROID_SDK_ROOT or EMULATOR.');
    }
    return emulator;
}

function fixturesDir() {
    return process.env.PDFR_FIXTURES || path.join(REPO_ROOT, 'build', 'test-fixtures');
}

function outDir(defaultName) {
    return path.join(REPO_ROOT, 'build', defaultName);
}

// The reader checks open these documents. They live under build/test-fixtures,
// which a clean build removes, so a missing file is regenerated before a test
// run instead of failing halfway through.
const REQUIRED_FIXTURES = ['sample.pdf', 'notes.txt', 'long.txt'];

/**
 * Makes sure the documents the reader checks need exist.
 *
 * The fixture generator is fast when `--no-gutenberg` is used: it writes the
 * PDF, text and image samples without downloading the public-domain books.
 * Returns the fixture directory.
 */
function ensureFixtures() {
    const directory = fixturesDir();
    const missing = () => REQUIRED_FIXTURES.filter(
        (name) => !isFile(path.join(directory, name)),
    );

    if (missing().length === 0) return directory;

    step(`Generating test fixtures in ${directory}`);
    const generator = path.join(__dirname, '..', 'make_test_files.js');
    const result = spawnSync(
        process.execPath,
        [generator, '--out', directory, '--no-gutenberg'],
        { cwd: REPO_ROOT, stdio: 'inherit', windowsHide: false },
    );
    if (result.error) throw result.error;
    const status = result.status ?? (result.signal ? 1 : 0);
    if (status !== 0) {
        throw new Error(`make_test_files.js failed with exit code ${status}.`);
    }

    const stillMissing = missing();
    if (stillMissing.length > 0) {
        throw new Error(
            `Fixtures are still missing after generation: ${stillMissing.join(', ')}. `
            + `Check ${directory}.`,
        );
    }
    return directory;
}

function say(message) {
    process.stdout.write(`${message}\n`);
}

function step(message) {
    process.stdout.write(`[PDFr] ${message}\n`);
}

function warn(message) {
    process.stderr.write(`[PDFr] ${message}\n`);
}

function sleep(milliseconds) {
    // The tools are interactive; a blocking sleep keeps the scripts simple and
    // matches what Start-Sleep did in the PowerShell versions.
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function formatBytes(bytes) {
    return bytes.toLocaleString('en-US');
}

/**
 * Waits until the delegate stops returning a falsy value.
 * Returns the delegate's last truthy result.
 */
function waitFor(delegate, { timeoutMs = 8000, intervalMs = 300, label = 'condition' } = {}) {
    const deadline = Date.now() + timeoutMs;
    let lastError = null;
    for (;;) {
        try {
            const value = delegate();
            if (value) return value;
        } catch (error) {
            lastError = error;
        }
        if (Date.now() >= deadline) {
            throw new Error(`Timed out waiting for ${label}.${lastError ? ` ${lastError.message}` : ''}`);
        }
        sleep(intervalMs);
    }
}

function waitUntilGone(delegate, { timeoutMs = 8000, intervalMs = 300 } = {}) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        try {
            if (!delegate()) return true;
        } catch {
            // The window can be mid-animation; keep polling.
        }
        if (Date.now() >= deadline) return false;
        sleep(intervalMs);
    }
}

/**
 * Runs a command and returns { status, stdout, stderr }.
 * `allowFailure` avoids throwing so a caller can inspect stderr.
 */
function run(file, args = [], options = {}) {
    const result = spawnSync(file, args, {
        cwd: options.cwd || REPO_ROOT,
        env: { ...process.env, ...(options.env || {}) },
        input: options.input,
        encoding: 'utf8',
        windowsHide: true,
        maxBuffer: options.maxBuffer || 64 * 1024 * 1024,
    });
    if (result.error) throw result.error;
    const output = {
        status: result.status ?? (result.signal ? 1 : 0),
        stdout: result.stdout || '',
        stderr: result.stderr || '',
        signal: result.signal,
    };
    if (output.status !== 0 && !options.allowFailure) {
        const detail = (output.stderr || output.stdout).trim();
        throw new Error(`${path.basename(file)} failed (${output.status}): ${args.join(' ')}${detail ? `\n${detail}` : ''}`);
    }
    return output;
}

function runStreaming(file, args = [], options = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(file, args, {
            cwd: options.cwd || REPO_ROOT,
            env: { ...process.env, ...(options.env || {}) },
            stdio: ['pipe', 'inherit', 'inherit'],
            windowsHide: options.windowsHide ?? false,
        });
        if (options.input !== undefined) child.stdin.end(options.input);
        else child.stdin.end();
        child.on('error', reject);
        child.on('close', (code, signal) => {
            const status = code ?? (signal ? 1 : 0);
            if (status !== 0 && !options.allowFailure) {
                reject(new Error(`${path.basename(file)} failed (${status}): ${args.join(' ')}`));
            } else {
                resolve({ status, signal });
            }
        });
    });
}

/** Runs a `.bat`/`.cmd` wrapper through cmd.exe with proper quoting. */
function runBatch(batch, args = [], options = {}) {
    return run(process.env.COMSPEC || 'cmd.exe', ['/d', '/c', batch, ...args], options);
}

function runBatchStreaming(batch, args = [], options = {}) {
    return runStreaming(process.env.COMSPEC || 'cmd.exe', ['/d', '/c', batch, ...args], options);
}

function which(command) {
    const extensions = isWindows() ? (process.env.PATHEXT || '.EXE;.BAT;.CMD').split(';') : [''];
    const directories = (process.env.PATH || '').split(path.delimiter);
    for (const directory of directories) {
        for (const extension of extensions) {
            const candidate = path.join(directory, command + extension);
            if (fs.existsSync(candidate)) return candidate;
        }
    }
    return null;
}

/** True when a path exists and is a file. */
function isFile(candidate) {
    try {
        return fs.statSync(candidate).isFile();
    } catch {
        return false;
    }
}

function ensureDir(directory) {
    fs.mkdirSync(directory, { recursive: true });
    return directory;
}

// ---------------------------------------------------------------------------
// Command-line parsing
// ---------------------------------------------------------------------------

function normaliseName(name) {
    return name.replace(/^-+/, '').replace(/[-_]/g, '').toLowerCase();
}

/**
 * Parses PowerShell-style switches.
 *
 * `spec` maps canonical names to `{ type, default, values }`, where type is
 * `'string'`, `'int'`, `'bool'`, or `'list'`. Names are matched without case,
 * dashes or underscores, so `-RamMb`, `--ram-mb` and `-rammb` all work.
 * A bare switch means true; `-Flag false` also works.
 */
function parseCli(argv, spec) {
    const byName = new Map();
    for (const [name, definition] of Object.entries(spec)) {
        byName.set(normaliseName(name), { name, ...definition });
    }

    const result = {};
    for (const [name, definition] of Object.entries(spec)) {
        result[name] = definition.default !== undefined
            ? definition.default
            : (definition.type === 'bool' ? false : (definition.type === 'list' ? [] : ''));
    }

    const positional = [];
    for (let index = 0; index < argv.length; index += 1) {
        const token = argv[index];
        if (!token.startsWith('-')) {
            positional.push(token);
            continue;
        }

        const equals = token.indexOf('=');
        let rawName = equals === -1 ? token : token.slice(0, equals);
        let inline = equals === -1 ? undefined : token.slice(equals + 1);
        const definition = byName.get(normaliseName(rawName));
        if (!definition) {
            warn(`Unknown option: ${rawName}`);
            continue;
        }

        if (definition.type === 'bool') {
            if (inline === undefined) {
                const next = argv[index + 1];
                if (next === 'true' || next === 'false') {
                    inline = next;
                    index += 1;
                }
            }
            result[definition.name] = inline === undefined ? true : inline !== 'false';
            continue;
        }

        let value = inline;
        if (value === undefined) {
            if (index + 1 >= argv.length) {
                throw new Error(`Option ${rawName} needs a value.`);
            }
            value = argv[index + 1];
            index += 1;
        }

        if (definition.type === 'int') {
            const parsed = Number.parseInt(value, 10);
            if (Number.isNaN(parsed)) throw new Error(`Option ${rawName} needs a whole number, got '${value}'.`);
            result[definition.name] = parsed;
        } else if (definition.type === 'list') {
            const pieces = Array.isArray(value) ? value : String(value).split(',');
            result[definition.name] = pieces.map((part) => part.trim()).filter(Boolean);
        } else if (definition.type === 'double') {
            const parsed = Number.parseFloat(value);
            if (Number.isNaN(parsed)) throw new Error(`Option ${rawName} needs a number, got '${value}'.`);
            result[definition.name] = parsed;
        } else {
            result[definition.name] = value;
        }
    }

    // `powershell -File script.ps1 -Abis a,b,c` used to arrive as one string.
    for (const [name, definition] of Object.entries(spec)) {
        if (definition.type === 'list' && result[name].length === 1 && result[name][0].includes(',')) {
            result[name] = result[name][0].split(',').map((part) => part.trim()).filter(Boolean);
        }
    }

    result._positional = positional;
    return result;
}

/** Runs `main` and turns a thrown Error into a friendly message and exit code. */
function runMain(main) {
    Promise.resolve()
        .then(main)
        .then((code) => {
            process.exitCode = typeof code === 'number' ? code : 0;
        })
        .catch((error) => {
            process.stderr.write(`[PDFr] ERROR: ${error && error.message ? error.message : error}\n`);
            process.exitCode = 1;
        });
}

module.exports = {
    REPO_ROOT,
    compareVersionsDesc,
    ensureDir,
    ensureFixtures,
    fixturesDir,
    formatBytes,
    isFile,
    isWindows,
    localStorage,
    outDir,
    parseCli,
    resolveAdb,
    resolveEmulator,
    resolveSdk,
    run,
    runBatch,
    runBatchStreaming,
    runMain,
    runStreaming,
    say,
    sleep,
    step,
    waitFor,
    waitUntilGone,
    warn,
    which,
};
