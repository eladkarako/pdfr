#!/usr/bin/env node
'use strict';

/**
 * Generates the test documents and copies them to the device's shared storage.
 *
 * The fixtures live in `/sdcard/Download/pdfr-fixtures`, which both the system
 * picker and the app can read without a private cache or a FileProvider URI.
 * Every file is pushed individually and its size is read back from the device,
 * so a truncated or missing copy fails the run instead of surfacing later as a
 * document that will not open. MediaStore is asked to index the files and the
 * result is checked, because a stale MediaStore row can make a picker show an
 * old file.
 *
 *     tools\push-fixtures.bat
 *     node tools\push-fixtures.js -SkipGenerate
 *     node tools\push-fixtures.js -NoGutenberg -Dest /sdcard/Download/pdfr
 *
 * Options:
 *   -Source DIR       fixture folder (default PDFR_FIXTURES or build\test-fixtures)
 *   -Dest PATH        shared-storage folder (default /sdcard/Download/pdfr-fixtures)
 *   -SkipGenerate     push the existing fixtures without regenerating them
 *   -NoGutenberg      skip the public-domain book downloads
 */

const fs = require('node:fs');
const path = require('node:path');
const {
    REPO_ROOT,
    ensureDir,
    fixturesDir,
    formatBytes,
    isFile,
    outDir,
    parseCli,
    resolveAdb,
    run,
    runMain,
    runTee,
    say,
    sleep,
    step,
    warn,
} = require('./lib/common');

const TOOLS_DIR = __dirname;
const DEFAULT_DEST = '/sdcard/Download/pdfr-fixtures';

/** Quotes one argument for the device's `sh`. */
function shellQuote(value) {
    return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

/** True when a path is on shared storage rather than app-private storage. */
function isSharedPath(candidate) {
    return /^\/(sdcard|storage\/emulated|mnt\/user|mnt\/sdcard)\//.test(candidate);
}

function adbShell(adb, command, options = {}) {
    return run(adb, ['shell', command], options);
}

/** Reads a file's size on the device, or null when it is missing. */
function remoteSize(adb, remote) {
    const quoted = shellQuote(remote);
    let result = adbShell(adb, `stat -c %s ${quoted}`, { allowFailure: true });
    let text = (result.stdout || '').trim();
    if (result.status !== 0 || !/^\d+$/.test(text)) {
        result = adbShell(adb, `wc -c < ${quoted}`, { allowFailure: true });
        text = (result.stdout || '').trim().split(/\s+/)[0];
    }
    return /^\d+$/.test(text) ? Number(text) : null;
}

/** Every regular file under `directory`, with forward-slash relative paths. */
function walk(directory, base = '') {
    const files = [];
    const entries = fs.readdirSync(directory, { withFileTypes: true })
        .sort((left, right) => left.name.localeCompare(right.name, 'en'));
    for (const entry of entries) {
        const absolute = path.join(directory, entry.name);
        const relative = base ? `${base}/${entry.name}` : entry.name;
        if (entry.isDirectory()) {
            files.push(...walk(absolute, relative));
        } else if (entry.isFile()) {
            files.push({ absolute, relative, size: fs.statSync(absolute).size });
        }
    }
    return files;
}

/** MediaStore stores /sdcard paths with the canonical /storage/emulated/0 root. */
function devicePath(remote) {
    return remote.replace(/^\/sdcard\//, '/storage/emulated/0/');
}

/** Asks MediaStore to scan one file, using the shell-callable method when present. */
function requestScan(adb, remote) {
    const quoted = shellQuote(remote);
    const result = adbShell(
        adb,
        `content call --uri content://media/external/file --method scan_file --arg ${quoted}`,
        { allowFailure: true },
    );
    if (result.status === 0 && /Result:/.test(`${result.stdout || ''}${result.stderr || ''}`)) {
        return;
    }
    // Older MediaProvider builds only expose the legacy broadcast. It can
    // leave a pending row behind, so it is the fallback rather than the norm.
    adbShell(
        adb,
        `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${shellQuote(`file://${remote}`)}`,
        { allowFailure: true },
    );
}

/** Returns the set of fully indexed MediaStore `_data` paths under `dest`. */
function indexedPaths(adb, dest) {
    const pattern = `${devicePath(dest)}/%`;
    // The whole expression is one argument to `content`; quoting only the
    // path would let the device shell split it into `--where _data LIKE ...`.
    const where = shellQuote(`_data LIKE '${pattern}'`);
    const command = 'content query --uri content://media/external/file'
        + ` --projection _data:is_pending:is_download --where ${where}`;
    const result = adbShell(adb, command, { allowFailure: true });
    const text = `${result.stdout || ''}${result.stderr || ''}`;
    const paths = new Set();
    for (const line of text.split(/\r?\n/)) {
        if (!line.startsWith('Row:')) continue;
        const fields = {};
        for (const match of line.matchAll(/(\w+)=([^,]*)/g)) {
            fields[match[1]] = match[2].trim();
        }
        // A pending row is a placeholder the picker will not list yet.
        if (fields._data && fields.is_pending === '0' && fields.is_download === '1') {
            paths.add(fields._data);
        }
    }
    return paths;
}

async function generate(source, options, logFile) {
    step(`Generating fixtures in ${source}...`);
    ensureDir(source);

    const args = [path.join(TOOLS_DIR, 'make_test_files.js'), '--out', source];
    if (options.NoGutenberg) args.push('--no-gutenberg');

    // The generator shells out to qpdf for the encrypted fixture; the bundled
    // copy in tools\ should win even when the user has not added it to PATH.
    const env = {
        PATH: `${TOOLS_DIR}${path.delimiter}${process.env.PATH || ''}`,
    };

    await runTee(process.execPath, args, {
        cwd: REPO_ROOT,
        env,
        logFile,
    });
}

async function main(argv) {
    const options = parseCli(argv, {
        Source: { type: 'string', default: fixturesDir() },
        Dest: { type: 'string', default: DEFAULT_DEST },
        SkipGenerate: { type: 'bool', default: false },
        NoGutenberg: { type: 'bool', default: false },
    });

    const source = path.resolve(options.Source);
    const dest = options.Dest.replace(/\/+$/, '');
    if (!isSharedPath(dest)) {
        throw new Error(
            `Refusing to push to '${dest}': it is not shared storage. `
            + 'Use a path under /sdcard or /storage/emulated/0.',
        );
    }

    const adb = resolveAdb();
    const logDir = ensureDir(outDir('logs'));
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const logFile = path.join(logDir, `push-fixtures-${stamp}.log`);
    step(`Log: ${logFile}`);

    if (!options.SkipGenerate) {
        await generate(source, options, logFile);
    } else if (!fs.existsSync(source)) {
        throw new Error(`Fixture folder not found: ${source}. Run without -SkipGenerate.`);
    }

    const files = walk(source);
    if (files.length === 0) {
        throw new Error(`No files to push in ${source}.`);
    }

    step(`Pushing ${files.length} files to ${dest} (shared storage)...`);
    adbShell(adb, `mkdir -p ${shellQuote(dest)}`);

    const directories = new Set(files.map((file) => path.posix.dirname(`${dest}/${file.relative}`)));
    for (const directory of [...directories].sort()) {
        adbShell(adb, `mkdir -p ${shellQuote(directory)}`);
    }

    let totalBytes = 0;
    for (const file of files) {
        const remote = `${dest}/${file.relative}`;
        // Push quietly and print one verified line per file; adb's progress
        // output still lands in the log.
        const result = await runTee(adb, ['push', file.absolute, remote], {
            logFile,
            quiet: true,
            allowFailure: true,
        });
        if (result.status !== 0) {
            throw new Error(`adb push failed for ${file.relative}. See ${logFile}.`);
        }

        const size = remoteSize(adb, remote);
        if (size === null) {
            throw new Error(`The device did not report a size for ${remote}.`);
        }
        if (size !== file.size) {
            throw new Error(
                `${file.relative} is ${formatBytes(file.size)} bytes locally but `
                + `${formatBytes(size)} bytes on the device.`,
            );
        }

        totalBytes += file.size;
        say(`  ${file.relative} (${formatBytes(file.size)} bytes)`);
    }

    step(`All ${files.length} files verified on the device (${formatBytes(totalBytes)} bytes).`);

    step('Asking MediaStore to index the files...');
    for (const file of files) {
        requestScan(adb, `${dest}/${file.relative}`);
    }

    const wanted = new Set(files.map((file) => devicePath(`${dest}/${file.relative}`)));
    let indexed = new Set();
    const deadline = Date.now() + 15000;
    for (;;) {
        indexed = indexedPaths(adb, dest);
        const missing = [...wanted].filter((candidate) => !indexed.has(candidate));
        if (missing.length === 0 || Date.now() >= deadline) break;
        sleep(1000);
    }

    const missing = [...wanted].filter((candidate) => !indexed.has(candidate));
    if (missing.length === 0) {
        say(`  MediaStore indexed all ${wanted.size} files.`);
    } else if (indexed.size === 0) {
        warn(
            'MediaStore did not index the folder. The files are on shared storage and '
            + 'the system picker still lists them, but MediaStore-based views may not.',
        );
    } else {
        warn(
            `MediaStore has not indexed ${missing.length} of ${wanted.size} files yet. `
            + 'The files are on shared storage; indexing can catch up in the background.',
        );
        for (const candidate of missing.slice(0, 5)) warn(`  ${candidate}`);
        if (missing.length > 5) warn(`  ... and ${missing.length - 5} more`);
    }

    say('');
    say(`[OK] Fixtures are in ${dest} (shared storage).`);
    say('     Open one with: tools\\open-fixture.bat sample.pdf');
    say('     Encrypted PDF password: secret');
    return 0;
}

runMain(() => main(process.argv.slice(2)));
