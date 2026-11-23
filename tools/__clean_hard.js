#!/usr/bin/env node
'use strict';

/**
 * Stops Gradle, kills leftover Java processes and removes every cache Gradle
 * can reuse, then runs a clean build preparation. This is the Node.js version
 * of the old __clean_hard.cmd, which called pwsh for each deletion.
 *
 * The project `build` folder is not removed as a whole: only the generated
 * `build/release` and `build/reports` trees are, so downloaded fixtures and the
 * qpdf sources survive. The log lives in `build/logs/__clean_hard.log`.
 *
 *     tools\__clean_hard.cmd
 *     node tools\__clean_hard.js
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
    REPO_ROOT,
    ensureDir,
    runBatch,
    runMain,
    say,
    sleep,
    step,
} = require('./lib/common');

const LOG_DIR = path.join(REPO_ROOT, 'build', 'logs');
const LOG = path.join(LOG_DIR, '__clean_hard.log');

function timestamp() {
    const now = new Date();
    const pad = (value) => String(value).padStart(2, '0');
    return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
        + `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

function appendLog(text) {
    fs.appendFileSync(LOG, text, 'utf8');
}

function runLogged(file, args, label) {
    step(label);
    const result = runBatch(file, args, { allowFailure: true });
    const output = `${result.stdout || ''}${result.stderr || ''}`;
    if (output) {
        appendLog(output);
        if (!output.endsWith('\n')) appendLog(os.EOL);
        process.stdout.write(output);
    }
    appendLog(os.EOL);
    appendLog(os.EOL);
    return result.status;
}

function removeTree(target) {
    step(`Removing ${target}`);
    try {
        fs.rmSync(target, { recursive: true, force: true });
        appendLog(`removed ${target}${os.EOL}${os.EOL}`);
    } catch (error) {
        appendLog(`could not fully remove ${target}: ${error.message}${os.EOL}${os.EOL}`);
    }
}

runMain(async () => {
    ensureDir(LOG_DIR);
    fs.writeFileSync(LOG, '', 'utf8');
    appendLog(`PDFr hard clean ${timestamp()}${os.EOL}`);
    appendLog(`repository ${REPO_ROOT}${os.EOL}${os.EOL}`);

    // No Java process may hold a file open while the caches are deleted.
    runBatch(path.join(REPO_ROOT, 'gradlew.bat'), ['--stop'], { allowFailure: true });
    sleep(5000);
    runBatch('taskkill.exe', ['/F', '/IM', 'java.exe', '/T'], { allowFailure: true });
    runBatch('taskkill.exe', ['/F', '/IM', 'javaw.exe', '/T'], { allowFailure: true });
    sleep(2000);

    removeTree(path.join(REPO_ROOT, '.gradle'));
    removeTree(path.join(os.homedir(), '.gradle'));
    removeTree(path.join(REPO_ROOT, '.kotlin'));
    removeTree(path.join(REPO_ROOT, 'build', 'release'));
    removeTree(path.join(REPO_ROOT, 'build', 'reports'));
    // build/test-fixtures is intentionally kept: it holds downloaded books and
    // generated sample documents that are expensive to recreate.

    const cleanStatus = runLogged(
        path.join(REPO_ROOT, 'gradlew.bat'),
        ['clean', '--console=plain'],
        'Running Gradle clean',
    );

    sleep(2000);
    runBatch(path.join(REPO_ROOT, 'gradlew.bat'), ['--stop'], { allowFailure: true });
    sleep(5000);
    runBatch('taskkill.exe', ['/F', '/IM', 'java.exe', '/T'], { allowFailure: true });
    runBatch('taskkill.exe', ['/F', '/IM', 'javaw.exe', '/T'], { allowFailure: true });

    say('');
    say(`[PDFr] Hard clean finished (gradle clean exit ${cleanStatus}).`);
    say(`[PDFr] Log: ${LOG}`);
    return cleanStatus === 0 ? 0 : 1;
});
