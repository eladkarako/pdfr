#!/usr/bin/env node
'use strict';

/**
 * Opens a fixture from the device's shared storage in PDFr.
 *
 * The old batch script copied the file into PDFr's private cache through
 * `run-as` and sent the app a FileProvider URI. That worked, but the artifact
 * then existed only inside the app, so it could not be inspected from the
 * device or opened again after a reinstall. This script keeps the workflow on
 * `/sdcard`: it copies the file there (verifying the size), then opens it with
 * a shared-storage document URI. When the platform refuses that grant, it
 * drives the system picker instead, which is the same route a person takes.
 *
 *     tools\open-fixture.bat sample.pdf
 *     tools\open-fixture.bat notes.txt text/plain
 *     node tools\open-fixture.js C:\docs\paper.pdf
 *     node tools\open-fixture.js -Method picker gutenberg\gutenberg-color.pdf
 *
 * Options:
 *   -Dest PATH        shared-storage folder (default /sdcard/Download/pdfr-fixtures)
 *   -Mime TYPE        override the MIME type guessed from the extension
 *   -Method MODE      auto (default), intent, or picker
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

const APP_ID = 'com.karako.pdfr';
const ACTIVITY = `${APP_ID}/.MainActivity`;
const DEFAULT_DEST = '/sdcard/Download/pdfr-fixtures';
const DOWNLOAD_ROOT = '/storage/emulated/0/Download/';
const UI_DUMP = '/sdcard/pdfr-open-ui.xml';

const MIME_TYPES = {
    '.pdf': 'application/pdf',
    '.epub': 'application/epub+zip',
    '.xps': 'application/vnd.ms-xpsdocument',
    '.oxps': 'application/oxps',
    '.cbz': 'application/vnd.comicbook+zip',
    '.cbt': 'application/x-cbt',
    '.fb2': 'application/x-fictionbook+xml',
    '.mobi': 'application/x-mobipocket-ebook',
    '.txt': 'text/plain',
    '.nfo': 'text/plain',
    '.text': 'text/plain',
    '.log': 'text/plain',
    '.cfg': 'text/plain',
    '.ini': 'text/plain',
    '.conf': 'text/plain',
    '.properties': 'text/plain',
    '.md': 'text/markdown',
    '.markdown': 'text/markdown',
    '.html': 'text/html',
    '.htm': 'text/html',
    '.xhtml': 'application/xhtml+xml',
    '.yaml': 'application/x-yaml',
    '.yml': 'application/x-yaml',
    '.toml': 'application/toml',
    '.json': 'application/json',
    '.jsonl': 'application/x-ndjson',
    '.ndjson': 'application/x-ndjson',
    '.xml': 'application/xml',
    '.csv': 'text/csv',
    '.tsv': 'text/tab-separated-values',
    '.js': 'application/javascript',
    '.mjs': 'application/javascript',
    '.cjs': 'application/javascript',
    '.sh': 'application/x-sh',
    '.bash': 'application/x-sh',
    '.c': 'text/x-c',
    '.h': 'text/x-c',
    '.cpp': 'text/x-c++',
    '.hpp': 'text/x-c++',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.ppm': 'image/x-portable-pixmap',
    '.pgm': 'image/x-portable-graymap',
    '.pbm': 'image/x-portable-bitmap',
    '.pnm': 'image/x-portable-anymap',
};

function mimeFor(name) {
    return MIME_TYPES[path.extname(name).toLowerCase()] || 'application/octet-stream';
}

/** Quotes one argument for the device's `sh`. */
function shellQuote(value) {
    return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

function adbShell(adb, command, options = {}) {
    return run(adb, ['shell', command], options);
}

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

/** Reads the MediaStore row state for one shared-storage path. */
function indexState(adb, remote) {
    const where = shellQuote(`_data='${remote}'`);
    const command = 'content query --uri content://media/external/file'
        + ` --projection _id:is_pending:is_download --where ${where}`;
    const result = adbShell(adb, command, { allowFailure: true });
    const text = `${result.stdout || ''}${result.stderr || ''}`;
    if (!/_id=\d+/.test(text)) return null;
    const pending = /is_pending=(\d+)/.exec(text);
    const download = /is_download=(\d+)/.exec(text);
    return {
        pending: pending ? pending[1] !== '0' : true,
        download: download ? download[1] === '1' : false,
    };
}

/** True when MediaStore has a complete row that the Downloads root will list. */
function isIndexed(adb, remote) {
    const state = indexState(adb, remote);
    if (!state || state.pending) return false;
    // The picker's Downloads root is backed by MediaStore.Downloads, not by a
    // plain directory listing, so is_download has to be set too.
    if (remote.startsWith(DOWNLOAD_ROOT) && !state.download) return false;
    return true;
}

/**
 * Asks MediaStore to scan the file and waits for a complete row.
 *
 * `content call ... scan_file` is the shell-callable scan on current
 * MediaProvider builds. The legacy broadcast is kept as a fallback for older
 * releases, where it may leave a pending row until the next scan.
 */
function ensureIndexed(adb, remote) {
    if (isIndexed(adb, remote)) return true;

    const quoted = shellQuote(remote);
    const scan = adbShell(
        adb,
        `content call --uri content://media/external/file --method scan_file --arg ${quoted}`,
        { allowFailure: true },
    );
    if (scan.status !== 0 || !/Result:/.test(`${scan.stdout || ''}${scan.stderr || ''}`)) {
        adbShell(
            adb,
            `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${shellQuote(`file://${remote}`)}`,
            { allowFailure: true },
        );
    }

    const deadline = Date.now() + 15000;
    for (;;) {
        if (isIndexed(adb, remote)) return true;
        if (Date.now() >= deadline) return false;
        sleep(500);
    }
}

function findByBaseName(directory, wanted, depth = 0) {
    if (depth > 8 || !fs.existsSync(directory)) return null;
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const candidate = path.join(directory, entry.name);
        if (entry.isFile() && entry.name.toLowerCase() === wanted.toLowerCase()) return candidate;
    }
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        if (!entry.isDirectory()) continue;
        const found = findByBaseName(path.join(directory, entry.name), wanted, depth + 1);
        if (found) return found;
    }
    return null;
}

/** Finds the local file, accepting a bare name, a relative path or a full path. */
function resolveLocal(name) {
    const candidates = [];
    if (path.isAbsolute(name)) {
        candidates.push(name);
    } else {
        candidates.push(path.resolve(process.cwd(), name));
        candidates.push(path.resolve(REPO_ROOT, name));
        candidates.push(path.resolve(fixturesDir(), name));
    }
    for (const candidate of candidates) {
        if (isFile(candidate)) return candidate;
    }
    return findByBaseName(fixturesDir(), path.basename(name));
}

// ---------------------------------------------------------------------------
// uiautomator
// ---------------------------------------------------------------------------

function decodeEntities(value) {
    return value.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (all, body) => {
        if (body.startsWith('#')) {
            const code = body[1] === 'x' || body[1] === 'X'
                ? Number.parseInt(body.slice(2), 16)
                : Number.parseInt(body.slice(1), 10);
            return Number.isFinite(code) ? String.fromCodePoint(code) : all;
        }
        const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }[body];
        return named === undefined ? all : named;
    });
}

function parseNodes(xml) {
    const nodes = [];
    for (const match of String(xml).matchAll(/<node\b[^>]*>/g)) {
        const attributes = {};
        for (const pair of match[0].matchAll(/([\w:-]+)="([^"]*)"/g)) {
            attributes[pair[1]] = decodeEntities(pair[2]);
        }
        if (attributes.bounds) nodes.push(attributes);
    }
    return nodes;
}

/** Dumps the current window and returns its nodes, retrying while it animates. */
function dumpUi(adb) {
    for (let attempt = 0; attempt < 3; attempt += 1) {
        adbShell(adb, `uiautomator dump ${UI_DUMP}`, { allowFailure: true });
        const result = run(adb, ['exec-out', 'cat', UI_DUMP], { allowFailure: true });
        const nodes = parseNodes(result.stdout || '');
        if (nodes.length > 0) return nodes;
        sleep(400);
    }
    return [];
}

function visible(node) {
    return node['visible-to-user'] !== 'false';
}

function label(node) {
    return `${node.text || ''} ${node['content-desc'] || ''}`.trim();
}

function centerOf(node) {
    const match = String(node.bounds || '').match(/\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
    if (!match) return null;
    return {
        x: Math.round((Number(match[1]) + Number(match[3])) / 2),
        y: Math.round((Number(match[2]) + Number(match[4])) / 2),
    };
}

function tap(adb, node) {
    const point = centerOf(node);
    if (!point) throw new Error(`The UI node '${label(node)}' has no tappable bounds.`);
    adbShell(adb, `input tap ${point.x} ${point.y}`);
}

function waitFor(adb, predicate, description, timeoutMs = 10000) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        const nodes = dumpUi(adb);
        const found = predicate(nodes);
        if (found) return found === true ? nodes : found;
        if (Date.now() >= deadline) {
            throw new Error(`Timed out waiting for ${description}.`);
        }
        sleep(400);
    }
}

function screenSize(adb) {
    const result = adbShell(adb, 'wm size', { allowFailure: true });
    const matches = [...(result.stdout || '').matchAll(/(\d+)x(\d+)/g)];
    if (matches.length === 0) return { width: 1080, height: 2340 };
    const last = matches[matches.length - 1];
    return { width: Number(last[1]), height: Number(last[2]) };
}

/** Scrolls the file grid down (finger moves up) by about half a screen. */
function scrollGrid(adb) {
    const size = screenSize(adb);
    const x = Math.round(size.width / 2);
    const start = Math.round(size.height * 0.78);
    const end = Math.round(size.height * 0.32);
    adbShell(adb, `input swipe ${x} ${start} ${x} ${end} 350`);
    sleep(500);
}

function findGridEntry(adb, name, attempts = 8) {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
        const nodes = dumpUi(adb);
        const exact = nodes.find((node) => (
            visible(node)
            && node['resource-id'] === 'android:id/title'
            && node.text === name
        ));
        if (exact) return exact;

        // Long names can be ellipsized in the caption while `text` stays full,
        // but some DocumentsUI builds truncate both; match the visible prefix.
        const prefix = name.length > 18 ? name.slice(0, 18) : name;
        const loose = nodes.find((node) => (
            visible(node)
            && node['resource-id'] === 'android:id/title'
            && node.text
            && node.text.startsWith(prefix)
        ));
        if (loose) return loose;

        if (attempt < attempts - 1) scrollGrid(adb);
    }
    throw new Error(`Could not find '${name}' in the document picker.`);
}

function isDocumentsUi(nodes) {
    return nodes.some((node) => /documentsui/.test(node.package || '') && visible(node));
}

function drawerIsOpen(nodes) {
    return nodes.some((node) => (
        node['resource-id'] === 'com.android.documentsui:id/roots_list' && visible(node)
    ));
}

function headerText(nodes) {
    const header = nodes.find((node) => (
        node['resource-id'] === 'com.android.documentsui:id/header_title' && visible(node)
    ));
    return header ? header.text || '' : '';
}

/** The breadcrumb trail, left to right. DocumentsUI labels the root "Files in X". */
function breadcrumbs(nodes) {
    return nodes
        .filter((node) => (
            node['resource-id'] === 'com.android.documentsui:id/breadcrumb_text' && visible(node)
        ))
        .sort((left, right) => (centerOf(left)?.x ?? 0) - (centerOf(right)?.x ?? 0))
        .map((node) => (node.text || '').trim())
        .filter(Boolean);
}

function folderName(nodes) {
    const crumbs = breadcrumbs(nodes);
    if (crumbs.length > 0) return crumbs[crumbs.length - 1];
    return headerText(nodes).replace(/^Files in /, '');
}

function ensureDownloadsRoot(adb) {
    let nodes = dumpUi(adb);
    if (!drawerIsOpen(nodes)) {
        const showRoots = nodes.find((node) => (
            visible(node) && node['content-desc'] === 'Show roots'
        ));
        if (!showRoots) {
            throw new Error('The document picker did not offer a roots button.');
        }
        tap(adb, showRoots);
        waitFor(adb, drawerIsOpen, 'the document picker roots drawer', 5000);
        nodes = dumpUi(adb);
    }

    const downloads = waitFor(
        adb,
        (current) => current.find((node) => (
            visible(node)
            && node['resource-id'] === 'android:id/title'
            && node.text === 'Downloads'
        )),
        'the Downloads root',
        5000,
    );
    tap(adb, downloads);
    waitFor(
        adb,
        (current) => folderName(current) === 'Downloads',
        'the Downloads folder',
        8000,
    );
    sleep(500);
}

function enterFolder(adb, name) {
    const entry = findGridEntry(adb, name);
    tap(adb, entry);
    waitFor(
        adb,
        (current) => folderName(current) === name,
        `the ${name} folder`,
        8000,
    );
    sleep(500);
}

function waitForDocument(adb, timeoutMs = 25000) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        const nodes = dumpUi(adb);
        const appNodes = nodes.filter((node) => node.package === APP_ID);
        if (appNodes.length > 0) {
            const refusal = appNodes.find((node) => (
                /can.?t open|cannot open|no access|permission denied|not allowed/i.test(label(node))
            ));
            if (refusal) {
                throw new Error(`The app refused the document: ${label(refusal)}`);
            }
            // The page-number field only exists once a document is laid out.
            // A password prompt also has an EditText, which still means the
            // document itself opened.
            const ready = appNodes.some((node) => (
                node.class === 'android.widget.EditText'
                || node['content-desc'] === 'Current page'
            ));
            if (ready) return true;
        }
        if (Date.now() >= deadline) return false;
        sleep(500);
    }
}

// ---------------------------------------------------------------------------
// Opening
// ---------------------------------------------------------------------------

function restartApp(adb) {
    // A previous picker task can stay on top of PDFr's task; starting the
    // activity would then only deliver the intent behind the picker.
    adbShell(adb, 'am force-stop com.android.documentsui', { allowFailure: true });
    adbShell(adb, 'am force-stop com.google.android.documentsui', { allowFailure: true });
    adbShell(adb, `am force-stop ${APP_ID}`, { allowFailure: true });
    adbShell(adb, `am start -n ${ACTIVITY}`, { allowFailure: true });
    waitFor(
        adb,
        (nodes) => nodes.some((node) => node.package === APP_ID && visible(node)),
        'the PDFr window',
        15000,
    );
    sleep(800);
}

function tapOpenButton(adb) {
    const node = waitFor(
        adb,
        (nodes) => nodes.find((candidate) => (
            visible(candidate)
            && (candidate['content-desc'] === 'Open document'
                || candidate['content-desc'] === 'Open a document'
                || candidate.text === 'Open a document')
        )),
        'the Open document button',
        8000,
    );
    tap(adb, node);
    waitFor(adb, isDocumentsUi, 'the system document picker', 10000);
}

/** Tries the shared-storage document URI, which needs no picker interaction. */
function openWithIntent(adb, remote, mime) {
    if (!remote.startsWith('/storage/emulated/0/')) return false;
    const documentId = `primary:${remote.slice('/storage/emulated/0/'.length)}`;
    const uri = 'content://com.android.externalstorage.documents/document/'
        + encodeURIComponent(documentId);
    const command = `am start -a android.intent.action.VIEW -n ${ACTIVITY}`
        + ` -d ${shellQuote(uri)} -t ${shellQuote(mime)} --grant-read-uri-permission`;
    const result = adbShell(adb, command, { allowFailure: true });
    if (result.status !== 0) return false;
    return waitForDocument(adb, 12000);
}

/** Drives the system picker: Open document -> roots -> Downloads -> folders. */
function openWithPicker(adb, remote) {
    if (!remote.startsWith(DOWNLOAD_ROOT)) {
        throw new Error(
            `The picker route can only navigate inside Download; ${remote} is outside it. `
            + 'Use -Method intent or push the file under Download.',
        );
    }
    const relative = remote.slice(DOWNLOAD_ROOT.length);
    const parts = relative.split('/').filter(Boolean);
    const fileName = parts.pop();

    tapOpenButton(adb);
    ensureDownloadsRoot(adb);
    for (const folder of parts) enterFolder(adb, folder);
    const entry = findGridEntry(adb, fileName);
    tap(adb, entry);

    if (!waitForDocument(adb, 30000)) {
        throw new Error(`PDFr did not open ${fileName}. The file is in ${remote}.`);
    }
}

async function ensureRemote(adb, local, remote, logFile) {
    const localSize = fs.statSync(local).size;
    const existing = remoteSize(adb, remote);
    if (existing === localSize) {
        say(`  ${remote} is already up to date (${formatBytes(localSize)} bytes).`);
        return;
    }

    step(`Copying ${path.basename(local)} to ${remote}...`);
    adbShell(adb, `mkdir -p ${shellQuote(path.posix.dirname(remote))}`);
    const result = await runTee(adb, ['push', local, remote], {
        logFile,
        quiet: true,
        allowFailure: true,
    });
    if (result.status !== 0) {
        throw new Error(`adb push failed for ${local}. See ${logFile}.`);
    }

    const verified = remoteSize(adb, remote);
    if (verified !== localSize) {
        throw new Error(
            `${path.basename(local)} is ${formatBytes(localSize)} bytes locally but `
            + `${verified === null ? 'missing' : `${formatBytes(verified)} bytes`} on the device.`,
        );
    }
    say(`  verified ${remote} (${formatBytes(localSize)} bytes).`);
}

async function main(argv) {
    const options = parseCli(argv, {
        Dest: { type: 'string', default: DEFAULT_DEST },
        Mime: { type: 'string', default: '' },
        Method: { type: 'string', default: 'auto' },
    });

    const name = options._positional[0];
    if (!name) {
        throw new Error('Usage: open-fixture.bat <file-name-or-path> [mime-type]');
    }
    // The batch wrapper has always accepted the MIME type as a second
    // positional argument; keep that working beside -Mime.
    const positionalMime = options._positional[1] || '';
    if (!['auto', 'intent', 'picker'].includes(options.Method)) {
        throw new Error(`Unknown -Method '${options.Method}'. Use auto, intent or picker.`);
    }

    const dest = options.Dest.replace(/\/+$/, '');
    const adb = resolveAdb();
    const logFile = path.join(ensureDir(outDir('logs')), 'open-fixture.log');

    let local = null;
    let remote = null;
    if (name.startsWith('/sdcard/') || name.startsWith('/storage/')) {
        remote = name.replace(/^\/sdcard\//, '/storage/emulated/0/');
        say(`Using the file already on the device: ${remote}`);
    } else {
        local = resolveLocal(name);
        if (!local) {
            throw new Error(
                `'${name}' was not found in the current folder, the repository or `
                + `${fixturesDir()}. Run tools\\push-fixtures.bat first, or pass a full path.`,
            );
        }

        let relative;
        const fixtures = path.resolve(fixturesDir());
        const absolute = path.resolve(local);
        if (absolute.toLowerCase().startsWith(`${fixtures.toLowerCase()}${path.sep}`)) {
            relative = path.relative(fixtures, absolute).replace(/\\/g, '/');
        } else {
            relative = path.basename(absolute);
        }
        remote = `${dest}/${relative}`;
        await ensureRemote(adb, absolute, remote, logFile);
    }

    // MediaStore and the picker both report the canonical /storage/emulated/0
    // path even when the caller used /sdcard.
    remote = remote.replace(/^\/sdcard\//, '/storage/emulated/0/');

    const mime = options.Mime || positionalMime || mimeFor(remote);
    if (!ensureIndexed(adb, remote)) {
        warn(`MediaStore has not indexed ${remote}; the system picker may not list it.`);
    }
    restartApp(adb);

    let opened = false;
    if (options.Method !== 'picker') {
        step(`Opening ${path.posix.basename(remote)} (${mime}) from shared storage...`);
        opened = openWithIntent(adb, remote, mime);
        if (!opened && options.Method === 'intent') {
            throw new Error('The shared-storage document URI was refused. Try -Method picker.');
        }
    }

    if (!opened) {
        if (options.Method === 'auto') {
            warn('The document URI was refused; using the system picker instead.');
            restartApp(adb);
        }
        step(`Opening ${path.posix.basename(remote)} through the system picker...`);
        openWithPicker(adb, remote);
    }

    say(`[OK] PDFr opened ${path.posix.basename(remote)} from ${remote}.`);
    return 0;
}

runMain(() => main(process.argv.slice(2)));
