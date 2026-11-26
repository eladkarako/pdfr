#!/usr/bin/env node
'use strict';

/**
 * Runs the reader's UI checks on a connected device and fails on any regression.
 *
 * Node.js port of the former tools\reader-checks.ps1. It drives the installed
 * debug build with adb and uiautomator, reads the Compose semantics tree, and
 * compares screenshots where only pixels can prove the result. Sections,
 * switches, output, screenshots, results.txt and exit codes match the
 * PowerShell version:
 *
 *     node tools\reader-checks.js
 *     node tools\reader-checks.js -Section text
 *     node tools\reader-checks.js -Section gestures -SkipRoot
 *
 * The gesture checks need adb root, exactly like zoom-probe.js. Run the other
 * sections on a retail device with -Section empty,text,selection,metadata,exit.
 * Screen coordinates and the multitouch device default to the pdfr_full AVD
 * (1080x2340); override -ScreenWidth/-ScreenHeight/-EventDevice for a phone.
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const {
    REPO_ROOT,
    ensureDir,
    ensureFixtures,
    parseCli,
    resolveAdb,
    run,
    runBatch,
    runMain,
    say,
    sleep,
} = require('./lib/common');
const { readPng } = require('./lib/png');

// ---------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------

const ALLOWED_SECTIONS = ['all', 'empty', 'text', 'selection', 'metadata', 'gestures', 'exit'];
const DEFAULT_SECTIONS = ['empty', 'text', 'selection', 'metadata', 'gestures', 'exit'];

const options = parseCli(process.argv.slice(2), {
    Section: { type: 'list', default: ['all'] },
    ScreenWidth: { type: 'int', default: 1080 },
    ScreenHeight: { type: 'int', default: 2340 },
    EventDevice: { type: 'string', default: '/dev/input/event2' },
    OutDir: { type: 'string', default: '' },
    // Skip `adb root` when adbd is already root (avoids a restart that can
    // resize the viewport between screenshots).
    SkipRoot: { type: 'bool', default: false },
    // Leave the app on whatever the last check opened.
    KeepOpen: { type: 'bool', default: false },
});

let adb = '';
let outDir = '';

// ---------------------------------------------------------------------------
// Screenshot reader
//
// The C# PdfrImage helper from the PowerShell version, translated to the RGBA
// buffers returned by lib/png. Pixel loops stay on the Buffer in tight loops:
// a 1080x2340 screenshot has 2.5 million pixels and the per-pixel checks are
// what made this fast enough to run in a normal CI pass.
// ---------------------------------------------------------------------------

class PdfrImage {
    constructor(file) {
        const image = readPng(file);
        this.width = image.width;
        this.height = image.height;
        this.data = image.data;
    }

    /** Counts pixels whose average channel value is darker than 120. */
    inkCount(left, top, right, bottom) {
        const { width, height, data } = this;
        const firstX = Math.max(0, left);
        const lastX = Math.min(width, right);
        const firstY = Math.max(0, top);
        const lastY = Math.min(height, bottom);
        let count = 0;

        for (let y = firstY; y < lastY; y += 1) {
            let offset = (y * width + firstX) * 4;
            for (let x = firstX; x < lastX; x += 1, offset += 4) {
                const red = data[offset];
                const green = data[offset + 1];
                const blue = data[offset + 2];
                // (red + green + blue) / 3 < 120 with C# integer division.
                if (red + green + blue < 360) count += 1;
            }
        }

        return count;
    }

    // Selection fills and handles are the only strongly blue pixels in the
    // reading area. Counting them leaves the line numbers, which are grey,
    // and the white page untouched.
    measureSelection(gutterWidth) {
        const { width, height, data } = this;
        const stats = {
            count: 0,
            gutter: 0,
            dark: 0,
            minX: Number.MAX_SAFE_INTEGER,
            minY: Number.MAX_SAFE_INTEGER,
            maxX: 0,
            maxY: 0,
        };

        const top = Math.trunc(height * 0.14);
        const bottom = Math.trunc(height * 0.94);
        for (let y = top; y < bottom; y += 2) {
            const row = y * width * 4;
            for (let x = 0; x < width; x += 2) {
                const offset = row + x * 4;
                const red = data[offset];
                const green = data[offset + 1];
                const blue = data[offset + 2];

                if (blue - red > 20 && blue - green > 10) {
                    stats.count += 1;
                    if (x < gutterWidth) stats.gutter += 1;
                    if (red + green + blue < 360) stats.dark += 1;
                    if (x < stats.minX) stats.minX = x;
                    if (x > stats.maxX) stats.maxX = x;
                    if (y < stats.minY) stats.minY = y;
                    if (y > stats.maxY) stats.maxY = y;
                }
            }
        }

        if (stats.count === 0) {
            stats.minX = 0;
            stats.minY = 0;
        }
        return stats;
    }

    static compare(fileA, fileB) {
        const a = new PdfrImage(fileA);
        const b = new PdfrImage(fileB);

        if (a.width !== b.width || a.height !== b.height) {
            return { comparable: false, mean: 255.0, changed: 1.0 };
        }

        const top = Math.trunc(a.height * 0.14);
        const bottom = Math.trunc(a.height * 0.90);
        const step = 4;
        let total = 0.0;
        let changed = 0;
        let count = 0;

        for (let y = top; y < bottom; y += step) {
            let offsetA = y * a.width * 4;
            let offsetB = y * b.width * 4;
            for (let x = 0; x < a.width; x += step) {
                const difference = (
                    Math.abs(a.data[offsetA] - b.data[offsetB]) +
                    Math.abs(a.data[offsetA + 1] - b.data[offsetB + 1]) +
                    Math.abs(a.data[offsetA + 2] - b.data[offsetB + 2])
                ) / 3.0;
                total += difference;
                count += 1;
                if (difference > 24) changed += 1;
                offsetA += step * 4;
                offsetB += step * 4;
            }
        }

        return {
            comparable: true,
            mean: count > 0 ? total / count : 0.0,
            changed: count > 0 ? changed / count : 0.0,
        };
    }
}

// ---------------------------------------------------------------------------
// Checks and logging
// ---------------------------------------------------------------------------

const checks = [];
let currentSection = '';

function writeResult(name, ok, detail = '') {
    checks.push({ section: currentSection, name, ok, detail });

    const prefix = ok ? '[PASS]' : '[FAIL]';
    if (detail) {
        say(`${prefix} ${name} - ${detail}`);
    } else {
        say(`${prefix} ${name}`);
    }
}

function testCheck(name, condition, detail = '') {
    writeResult(name, Boolean(condition), detail);
}

function testEqual(name, actual, expected) {
    // PowerShell's -eq compares strings without regard to case.
    const equal = String(actual ?? '').toLowerCase() === String(expected ?? '').toLowerCase();
    testCheck(name, equal, `expected '${actual ?? ''}', got '${actual ?? ''}'`);
}

function testMatch(name, actual, pattern) {
    const value = String(actual ?? '');
    if (matches(value, pattern)) {
        testCheck(name, true, `value '${value}'`);
    } else {
        testCheck(name, false, `value '${value}' does not match '${pattern}'`);
    }
}

function testSection(name, body) {
    currentSection = name;
    say('');
    say(`=== ${name} ===`);
    try {
        body();
    } catch (error) {
        let detail = error && error.message ? error.message : String(error);
        if (error && error.stack) {
            const firstBreak = error.stack.indexOf('\n');
            if (firstBreak >= 0) detail += `\n${error.stack.slice(firstBreak + 1).trimEnd()}`;
        }
        writeResult(`${name} section completed`, false, detail);
    }
}

// ---------------------------------------------------------------------------
// adb
// ---------------------------------------------------------------------------

function invokeAdb(args) {
    // adb routinely writes harmless notices to stderr (for example "Activity
    // not started"). Keep those from becoming errors while still reporting a
    // real non-zero exit code.
    const result = run(adb, args.map((argument) => String(argument)), { allowFailure: true });
    if (result.status !== 0) {
        throw new Error(
            `adb failed (${result.status}): ${args.join(' ')}\n${result.stdout}${result.stderr}`,
        );
    }
    return `${result.stdout}${result.stderr}`;
}

/** Runs adb without checking the exit code or surfacing stderr. */
function adbCapture(args) {
    return run(adb, args.map((argument) => String(argument)), { allowFailure: true }).stdout;
}

function saveShot(name) {
    const remote = `/sdcard/pdfr-checks-${name}.png`;
    const local = path.join(outDir, `${name}.png`);
    invokeAdb(['shell', `screencap -p ${remote}`]);
    invokeAdb(['pull', remote, local]);
    invokeAdb(['shell', 'rm', remote]);
    say(`[PDFr] ${local}`);
    return local;
}

// ---------------------------------------------------------------------------
// uiautomator XML
// ---------------------------------------------------------------------------

const BOUNDS_PATTERN = /^\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]$/;

function decodeEntities(value) {
    return value.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (all, body) => {
        if (body.startsWith('#')) {
            const code = body[1] === 'x' || body[1] === 'X'
                ? Number.parseInt(body.slice(2), 16)
                : Number.parseInt(body.slice(1), 10);
            return Number.isFinite(code) ? String.fromCodePoint(code) : all;
        }
        const named = {
            amp: '&',
            lt: '<',
            gt: '>',
            quot: '"',
            apos: "'",
        }[body];
        return named === undefined ? all : named;
    });
}

function parseAttributes(text) {
    const attributes = {};
    const pattern = /([^\s=\/>]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
    let match;
    while ((match = pattern.exec(text)) !== null) {
        attributes[match[1]] = decodeEntities(match[2] === undefined ? match[3] : match[2]);
    }
    return attributes;
}

/** Minimal XML reader for the uiautomator dump (no external packages). */
function parseXml(text) {
    const source = String(text)
        .replace(/^\uFEFF/, '')
        .replace(/<!--[\s\S]*?-->/g, '');
    const document = { name: '#document', attributes: {}, children: [], parent: null };
    const stack = [document];
    // Ordinary elements only; the <?xml ...?> declaration and <!DOCTYPE> are
    // skipped because they do not match the closing `>`.
    const tags = /<([!?/]?)([A-Za-z_][\w.:-]*)((?:\s+[^\s=\/>]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(\/?)>/g;
    let match;

    while ((match = tags.exec(source)) !== null) {
        const prefix = match[1];
        const name = match[2];
        const selfClosing = match[4] === '/';

        if (prefix === '/') {
            for (let index = stack.length - 1; index > 0; index -= 1) {
                if (stack[index].name === name) {
                    stack.length = index;
                    break;
                }
            }
            continue;
        }
        if (prefix === '!' || prefix === '?') continue;

        const element = {
            name,
            attributes: parseAttributes(match[3]),
            children: [],
            parent: stack[stack.length - 1],
        };
        stack[stack.length - 1].children.push(element);
        if (!selfClosing) stack.push(element);
    }

    const root = document.children[0];
    if (!root) throw new Error('No XML root element');
    return root;
}

function attr(node, name) {
    if (!node) return '';
    const value = node.attributes[name];
    return value === undefined ? '' : value;
}

function equalsIgnoreCase(left, right) {
    return String(left ?? '').toLowerCase() === String(right ?? '').toLowerCase();
}

function containsIgnoreCase(values, wanted) {
    return values.some((value) => equalsIgnoreCase(value, wanted));
}

/** All named descendants in document order. */
function descendants(root, name) {
    const found = [];
    const stack = [];
    for (let index = root.children.length - 1; index >= 0; index -= 1) {
        stack.push(root.children[index]);
    }
    while (stack.length > 0) {
        const node = stack.pop();
        if (node.name === name) found.push(node);
        for (let index = node.children.length - 1; index >= 0; index -= 1) {
            stack.push(node.children[index]);
        }
    }
    return found;
}

function cloneElement(element, parent) {
    const copy = {
        name: element.name,
        attributes: { ...element.attributes },
        children: [],
        parent,
    };
    copy.children = element.children.map((child) => cloneElement(child, copy));
    return copy;
}

function mergeAppWindows(dump) {
    // A --windows dump has a <displays> root. Fold the windows into one
    // hierarchy so every existing //node lookup keeps working, while dropping
    // the launcher and system chrome (status bar, clipboard preview) that never
    // matter to these checks. Nested windows repeat their parent's subtree, so
    // only direct display children are considered.
    const root = { name: 'hierarchy', attributes: {}, children: [], parent: null };
    if (dump.name !== 'displays') return root;

    for (const display of dump.children.filter((child) => child.name === 'display')) {
        for (const window of display.children.filter((child) => child.name === 'window')) {
            const first = descendants(window, 'node').find((node) => attr(node, 'package'));
            if (!first) continue;
            const packageName = attr(first, 'package');
            if (equalsIgnoreCase(packageName, 'com.android.launcher3')) continue;
            if (equalsIgnoreCase(packageName, 'com.android.systemui')) continue;

            for (const hierarchy of window.children.filter((child) => child.name === 'hierarchy')) {
                for (const child of hierarchy.children) {
                    root.children.push(cloneElement(child, root));
                }
            }
        }
    }

    return root;
}

function getUiXml() {
    const remote = '/sdcard/pdfr-checks-window.xml';
    const local = path.join(outDir, 'window.xml');

    for (let attempt = 0; attempt < 4; attempt += 1) {
        // --windows also dumps the Compose text context menu, which lives in
        // its own Popup Window rather than in the activity window.
        run(adb, ['shell', 'uiautomator', 'dump', '--windows', remote], { allowFailure: true });
        run(adb, ['pull', remote, local], { allowFailure: true });

        if (fs.existsSync(local)) {
            let size = 0;
            try {
                size = fs.statSync(local).size;
            } catch {
                size = 0;
            }
            if (size > 100) {
                try {
                    return mergeAppWindows(parseXml(fs.readFileSync(local, 'utf8')));
                } catch {
                    // The pull can race the dump; retry.
                }
            }
        }
        sleep(400);
    }

    throw new Error('Could not read the UI hierarchy from uiautomator.');
}

function compile(pattern) {
    return pattern instanceof RegExp ? pattern : new RegExp(pattern, 'i');
}

function matches(value, pattern) {
    if (value === undefined || value === null) return false;
    return compile(pattern).test(String(value));
}

function findNode(pattern, xml = null) {
    const tree = xml || getUiXml();
    const regex = compile(pattern);
    // Nodes that are not laid out (for example from a popup that is closing)
    // carry no bounds and cannot be tapped, so skip them.
    for (const node of descendants(tree, 'node')) {
        if (!BOUNDS_PATTERN.test(attr(node, 'bounds'))) continue;
        if (regex.test(attr(node, 'text')) || regex.test(attr(node, 'content-desc'))) {
            return node;
        }
    }
    return null;
}

function getNodeRect(node) {
    const bounds = node ? attr(node, 'bounds') : '';
    const match = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(bounds);
    if (!match) throw new Error(`Could not parse bounds '${bounds}'.`);

    const left = Number.parseInt(match[1], 10);
    const top = Number.parseInt(match[2], 10);
    const right = Number.parseInt(match[3], 10);
    const bottom = Number.parseInt(match[4], 10);

    return {
        left,
        top,
        right,
        bottom,
        width: right - left,
        height: bottom - top,
        x: Math.round((left + right) / 2),
        y: Math.round((top + bottom) / 2),
    };
}

function tapNode(node) {
    const rect = getNodeRect(node);
    invokeAdb(['shell', 'input', 'tap', rect.x, rect.y]);
    sleep(500);
}

function tapPattern(pattern) {
    tapNode(findNode(pattern));
}

function waitForPattern(pattern, timeoutMs = 8000) {
    const deadline = Date.now() + timeoutMs;
    do {
        try {
            const node = findNode(pattern);
            if (node) return node;
        } catch {
            // The window can be mid-animation; keep polling.
        }
        sleep(300);
    } while (Date.now() < deadline);

    throw new Error(`Timed out waiting for '${pattern}'.`);
}

function waitForGone(pattern, timeoutMs = 8000) {
    const deadline = Date.now() + timeoutMs;
    do {
        try {
            if (!findNode(pattern)) return true;
        } catch {
            // A dump failure means the window is changing; retry.
        }
        sleep(300);
    } while (Date.now() < deadline);

    return false;
}

function getActionNode(description) {
    const xml = getUiXml();
    return descendants(xml, 'node')
        .find((node) => equalsIgnoreCase(attr(node, 'content-desc'), description)) || null;
}

function testNodeEnabled(node) {
    if (!node) return null;

    let parent = node.parent;
    while (parent && parent.name === 'node') {
        if (equalsIgnoreCase(attr(parent, 'clickable'), 'true')) {
            return equalsIgnoreCase(attr(parent, 'enabled'), 'true');
        }
        parent = parent.parent;
    }
    return equalsIgnoreCase(attr(node, 'enabled'), 'true');
}

function testActionEnabled(description) {
    return testNodeEnabled(getActionNode(description));
}

function getActionRect(description) {
    const node = getActionNode(description);
    if (!node) throw new Error(`No toolbar action '${description}'.`);

    let parent = node.parent;
    while (parent && parent.name === 'node') {
        if (equalsIgnoreCase(attr(parent, 'clickable'), 'true')) {
            return getNodeRect(parent);
        }
        parent = parent.parent;
    }
    return getNodeRect(node);
}

/**
 * Insets past the classic 2 px bevel and the touch padding around it, so ink
 * checks see the icon or caption only. A disabled key keeps a full raised
 * border in the Windows 98 style, which would otherwise count as dark ink.
 */
function insetOf(rect) {
    return Math.round(Math.min(rect.width, rect.height) * 0.25);
}

function testPackageVisible(packageName) {
    const xml = getUiXml();
    return descendants(xml, 'node').some((node) => equalsIgnoreCase(attr(node, 'package'), packageName));
}

const MENU_PATTERN = '^(Fix PDF|Copy all|Text size:|Single page:|Line wrap:|Align right:|Line numbers:|Monospace:|Reorder pages|Rotate pages|Normalize|Exit)';

function testMenuOpen() {
    try {
        return Boolean(findNode(MENU_PATTERN));
    } catch {
        return false;
    }
}

function closePopup() {
    if (testMenuOpen()) {
        // BACK only reaches here when a popup really is open, so it cannot
        // close the reader by accident.
        invokeAdb(['shell', 'input', 'keyevent', 4]);
        sleep(350);
    }
}

function openOverflow() {
    closePopup();
    const button = getActionNode('More options');
    if (!button) throw new Error('The overflow button is not on screen.');
    tapNode(button);
    waitForPattern(MENU_PATTERN);
}

function getMenuLabels() {
    openOverflow();
    const xml = getUiXml();
    const labels = descendants(xml, 'node')
        .filter((node) => matches(attr(node, 'text'), MENU_PATTERN))
        .map((node) => attr(node, 'text'));

    invokeAdb(['shell', 'input', 'keyevent', 4]);
    sleep(350);
    return labels;
}

function escapeRegex(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function getMenuLabel(label) {
    openOverflow();
    const node = findNode(`^${escapeRegex(label)}:`);
    if (!node) {
        invokeAdb(['shell', 'input', 'keyevent', 4]);
        sleep(300);
        throw new Error(`No menu item '${label}'.`);
    }
    const text = attr(node, 'text');
    invokeAdb(['shell', 'input', 'keyevent', 4]);
    sleep(350);
    return text;
}

function toggleMenuItem(label) {
    openOverflow();
    const node = findNode(`^${escapeRegex(label)}:`);
    if (!node) {
        invokeAdb(['shell', 'input', 'keyevent', 4]);
        sleep(300);
        throw new Error(`No menu item '${label}'.`);
    }
    tapNode(node);
    sleep(400);
}

function getTextSizeDialogXml() {
    openOverflow();
    tapPattern('^Text size:');
    waitForPattern('^\\d+ pt$');
    return getUiXml();
}

function setTextSize(points) {
    const xml = getTextSizeDialogXml();
    const slider = descendants(xml, 'node')
        .find((node) => attr(node, 'class') === 'android.widget.SeekBar');
    if (!slider) throw new Error('The text-size slider is not on screen.');

    const rect = getNodeRect(slider);
    const fraction = (points - 5.0) / 35.0;
    const tapX = Math.round(rect.left + rect.width * fraction);
    invokeAdb(['shell', 'input', 'tap', tapX, rect.y]);
    sleep(400);

    const after = getUiXml();
    const value = descendants(after, 'node')
        .find((node) => matches(attr(node, 'text'), '^\\d+ pt$'));

    return {
        slider: rect,
        value: value ? attr(value, 'text') : '',
    };
}

function openEmptyApp() {
    invokeAdb(['shell', 'am force-stop com.karako.pdfr']);
    invokeAdb(['shell', 'am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.karako.pdfr/.MainActivity']);
    waitForPattern('^Nothing open$', 12000);
}

function openDoc(name) {
    const opener = path.join(__dirname, 'open-fixture.bat');
    const result = runBatch(opener, [name], { allowFailure: true });
    if (result.status !== 0) {
        throw new Error(`open-fixture.bat failed for '${name}'.`);
    }

    sleep(2000);
    const deadline = Date.now() + 12000;
    do {
        if (testActionEnabled('Share document') === true) return;
        sleep(400);
    } while (Date.now() < deadline);

    throw new Error(`The reader did not open '${name}'.`);
}

function getEditNodes(xml) {
    return descendants(xml, 'node')
        .filter((node) => attr(node, 'class') === 'android.widget.EditText');
}

function setEditText(node, value) {
    tapNode(node);
    invokeAdb(['shell', 'input', 'keyevent', 'KEYCODE_MOVE_END']);
    const deletes = Array(80).fill('67').join(' ');
    invokeAdb(['shell', `input keyevent ${deletes}`]);
    if (value) {
        invokeAdb(['shell', `input text ${value}`]);
    }
    sleep(500);
}

function openInfoDialog() {
    closePopup();
    tapPattern('^Document info$');
    waitForPattern('^Cancel$');
    return getUiXml();
}

function closeInfoDialog(button) {
    tapPattern(`^${button}$`);
    waitForGone('^Apply$', 10000);
    sleep(500);
}

function dismissDialog() {
    // x is left of the modal surface, so this lands on the scrim and keeps
    // the ViewModel's draft (the documented dismiss behaviour).
    invokeAdb(['shell', 'input', 'tap', 40, Math.round(options.ScreenHeight * 0.45)]);
    sleep(600);
}

function enableRoot() {
    if (options.SkipRoot) {
        const identity = adbCapture(['shell', 'id']).trim();
        if (!/uid=0/.test(identity)) {
            throw new Error("Raw multitouch needs root. Run 'adb root', or omit -SkipRoot.");
        }
        return;
    }

    run(adb, ['root'], { allowFailure: true });
    sleep(1000);
    run(adb, ['wait-for-device'], { allowFailure: true });
    sleep(2000);

    const identity = adbCapture(['shell', 'id']).trim();
    if (!/uid=0/.test(identity)) {
        throw new Error('Raw multitouch needs root. Use an emulator or a debuggable build.');
    }
}

function invokeZoomProbe(prefix, action, centerY = 0.45) {
    const probe = path.join(__dirname, 'zoom-probe.js');
    const probeArgs = [
        '-Action', action,
        '-EventDevice', options.EventDevice,
        '-ScreenWidth', options.ScreenWidth,
        '-ScreenHeight', options.ScreenHeight,
        '-CenterY', centerY,
        '-OutDir', outDir,
        '-Prefix', prefix,
    ];
    if (options.SkipRoot) probeArgs.push('-SkipRoot');

    const result = spawnSync(process.execPath, [probe, ...probeArgs.map(String)], {
        cwd: REPO_ROOT,
        stdio: 'inherit',
        windowsHide: false,
    });
    if (result.error) throw result.error;
    const status = result.status ?? (result.signal ? 1 : 0);
    if (status !== 0) {
        throw new Error(`zoom-probe.js failed with exit code ${status}.`);
    }
}

function getNumericNodes(xml) {
    return descendants(xml, 'node').filter((node) => (
        attr(node, 'class') === 'android.widget.TextView' && matches(attr(node, 'text'), '^\\d+$')
    ));
}

function getDialogHeight(xml) {
    // The modal window is the widest frame narrower than the screen. Picking
    // the tallest such frame measures the height cap without depending on the
    // internal Compose hierarchy.
    let tallest = 0;
    for (const node of descendants(xml, 'node')) {
        const match = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(attr(node, 'bounds'));
        if (!match) continue;
        const width = Number.parseInt(match[3], 10) - Number.parseInt(match[1], 10);
        const height = Number.parseInt(match[4], 10) - Number.parseInt(match[2], 10);
        if (width > options.ScreenWidth * 0.55 &&
            width < options.ScreenWidth * 0.99 &&
            height > 200 &&
            height < options.ScreenHeight - 100 &&
            height > tallest) {
            tallest = height;
        }
    }

    return tallest;
}

function formatDiff(diff) {
    // Matches PowerShell's '{0:N2}, changed {1:P1}' format.
    return `mean ${diff.mean.toFixed(2)}, changed ${(diff.changed * 100).toFixed(1)}%`;
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

function testEmptyState() {
    openEmptyApp();

    const xml = getUiXml();
    testCheck('shows the empty pane', Boolean(findNode('^Nothing open$', xml)));
    testCheck('Open document is enabled', testActionEnabled('Open document') === true);

    for (const action of ['Share document', 'Document info', 'Save as']) {
        testCheck(`${action} is disabled`, testActionEnabled(action) === false);
    }

    // The overflow menu stays available with no document so Fix PDF can open
    // the picker itself.
    testCheck('More options is enabled', testActionEnabled('More options') === true);

    const shot = saveShot('empty');
    const image = new PdfrImage(shot);
    const openRect = getActionRect('Open document');
    testCheck('Open document icon is visible', (
        image.inkCount(
            openRect.left + insetOf(openRect),
            openRect.top + insetOf(openRect),
            openRect.right - insetOf(openRect),
            openRect.bottom - insetOf(openRect),
        ) > 200
    ));

    for (const action of ['Share document', 'Document info', 'Save as']) {
        const rect = getActionRect(action);
        // Measure the middle of the cap, not the classic bevel: a disabled
        // key keeps its black and grey border, which is ink but not content.
        const inset = insetOf(rect);
        const ink = image.inkCount(
            rect.left + inset,
            rect.top + inset,
            rect.right - inset,
            rect.bottom - inset,
        );
        testCheck(`${action} looks faded`, ink < 50, `ink pixels: ${ink}`);
    }

    const emptyLabels = getMenuLabels();
    testCheck('the empty menu offers Fix PDF', containsIgnoreCase(emptyLabels, 'Fix PDF'), emptyLabels.join(', '));
    // A single-item array collapses to a string when returned from a function,
    // so compare the joined labels rather than indexing.
    testCheck('the empty menu offers Fix PDF then Exit', emptyLabels.join(', ') === 'Fix PDF, Exit', emptyLabels.join(', '));

    // A disabled Compose button has no click listener; tapping its centre must
    // leave the empty pane in place instead of opening a dialog or picker.
    tapNode(getActionNode('Document info'));
    const afterInfo = getUiXml();
    testCheck('tapping info with no document does nothing', (
        Boolean(findNode('^Nothing open$', afterInfo)) && getEditNodes(afterInfo).length === 0
    ));

    tapNode(getActionNode('Save as'));
    sleep(600);
    testCheck('tapping Save as with no document does nothing', testPackageVisible('com.karako.pdfr'));
}

function testExit() {
    openEmptyApp();

    // Exit is deliberately last in every menu. With no document open the menu
    // is exactly Fix PDF then Exit.
    const labels = getMenuLabels();
    testCheck(
        'the empty menu ends with Exit',
        labels.length === 2 && equalsIgnoreCase(labels[labels.length - 1], 'Exit'),
        labels.join(', '),
    );

    openOverflow();
    tapPattern('^Exit$');
    sleep(2000);
    const pidText = adbCapture(['shell', 'pidof com.karako.pdfr']).trim();
    testCheck('Exit ends the PDFr process', pidText === '', `pid: ${pidText}`);
    testCheck('Exit leaves no PDFr screen behind', !testPackageVisible('com.karako.pdfr'));

    // A clean relaunch proves the task was removed rather than left in a
    // half-finished state.
    openEmptyApp();
    testCheck('the app starts again after Exit', Boolean(findNode('^Nothing open$')));
}

function testPlainTextSettings() {
    openDoc('notes.txt');
    waitForPattern('^PDFr plain text fixture$', 12000);

    const labels = getMenuLabels();
    testCheck('default text size is 12 pt', containsIgnoreCase(labels, 'Text size: 12 pt'), labels.join(', '));
    testCheck('plain text has no Fix PDF', !containsIgnoreCase(labels, 'Fix PDF'), labels.join(', '));
    testCheck('plain text menu ends with Exit', equalsIgnoreCase(labels[labels.length - 1], 'Exit'), labels.join(', '));
    testCheck('single page defaults to on', containsIgnoreCase(labels, 'Single page: on'), labels.join(', '));
    testCheck('line wrap defaults to on', containsIgnoreCase(labels, 'Line wrap: on'), labels.join(', '));
    testCheck('right alignment defaults to off', containsIgnoreCase(labels, 'Align right: off'), labels.join(', '));
    testCheck('line numbers default to on', containsIgnoreCase(labels, 'Line numbers: on'), labels.join(', '));
    testCheck('monospace defaults to on', containsIgnoreCase(labels, 'Monospace: on'), labels.join(', '));

    const xml = getUiXml();
    const numbers = getNumericNodes(xml);
    testCheck('line numbers are rendered', numbers.length >= 8, `found ${numbers.length}`);
    const leftNumbers = numbers.filter((node) => {
        const rect = getNodeRect(node);
        return rect.left < options.ScreenWidth * 0.15 && rect.right <= options.ScreenWidth * 0.15;
    });
    testCheck(
        'line numbers sit on the left in LTR',
        leftNumbers.length === numbers.length,
        `${leftNumbers.length} of ${numbers.length} in the left gutter`,
    );

    toggleMenuItem('Line numbers');
    const offLabel = getMenuLabel('Line numbers');
    testEqual('line numbers can be turned off', offLabel, 'Line numbers: off');
    testCheck('no line number nodes while off', getNumericNodes(getUiXml()).length === 0);

    toggleMenuItem('Line numbers');
    testCheck('line numbers come back on', getNumericNodes(getUiXml()).length >= 8);

    toggleMenuItem('Align right');
    const rtlLabel = getMenuLabel('Align right');
    testEqual('right alignment can be turned on', rtlLabel, 'Align right: on');
    const rtlNumbers = getNumericNodes(getUiXml());
    const rightNumbers = rtlNumbers.filter((node) => getNodeRect(node).left >= options.ScreenWidth * 0.80);
    testCheck(
        'line numbers move to the right in RTL',
        rtlNumbers.length >= 8 && rightNumbers.length === rtlNumbers.length,
        `${rightNumbers.length} of ${rtlNumbers.length} in the right gutter`,
    );

    toggleMenuItem('Align right');
    const ltrAgain = getNumericNodes(getUiXml());
    const leftAgain = ltrAgain.filter((node) => getNodeRect(node).right <= options.ScreenWidth * 0.15);
    testCheck(
        'line numbers return to the left',
        ltrAgain.length >= 8 && leftAgain.length === ltrAgain.length,
    );

    // With wrapping off a long line can scroll horizontally. A horizontal
    // swipe must move the text, which is the visible difference between this
    // mode and the default wrapped page.
    toggleMenuItem('Line wrap');
    testEqual('line wrap can be turned off', getMenuLabel('Line wrap'), 'Line wrap: off');
    const wrapBefore = saveShot('wrap-off-before');
    invokeAdb([
        'shell', 'input', 'swipe',
        Math.round(options.ScreenWidth * 0.85), 1200,
        Math.round(options.ScreenWidth * 0.20), 1200,
        450,
    ]);
    sleep(600);
    const wrapAfter = saveShot('wrap-off-after');
    const wrapDiff = PdfrImage.compare(wrapBefore, wrapAfter);
    testCheck(
        'unwrapped text scrolls horizontally',
        wrapDiff.comparable && (wrapDiff.mean > 1.0 || wrapDiff.changed > 0.01),
        formatDiff(wrapDiff),
    );

    toggleMenuItem('Line wrap');
    testEqual('line wrap can be restored', getMenuLabel('Line wrap'), 'Line wrap: on');

    toggleMenuItem('Single page');
    testEqual('single page can be turned off', getMenuLabel('Single page'), 'Single page: off');
    const paged = getUiXml();
    const currentPage = findNode('^Current page$', paged);
    const totalPages = findNode('^Total pages$', paged);
    testCheck(
        'paged mode shows the page bar',
        Boolean(currentPage && totalPages),
        currentPage && totalPages
            ? `${attr(currentPage, 'text')} of ${attr(totalPages, 'text')}`
            : 'no page bar',
    );

    toggleMenuItem('Single page');
    testEqual('single page can be restored', getMenuLabel('Single page'), 'Single page: on');

    const size = setTextSize(22);
    testEqual('the text-size dialog follows the slider', size.value, '22 pt');
    closeInfoDialog('Apply');
    testEqual('the chosen text size is applied', getMenuLabel('Text size'), 'Text size: 22 pt');

    // Reopening proves the value survives closing the dialog; a full force-stop
    // is deliberately avoided here because that resets the reader defaults.
    const reopened = getTextSizeDialogXml();
    const reopenedValue = descendants(reopened, 'node')
        .find((node) => matches(attr(node, 'text'), '^22 pt$')) || null;
    testCheck('the text size persists while the document stays open', Boolean(reopenedValue));
    tapPattern('^Cancel$');
    waitForGone('^Cancel$', 5000);

    // Fixed-layout documents keep pinch zoom; they must not expose text size.
    openDoc('sample.pdf');
    const pdfLabels = getMenuLabels();
    testCheck(
        'PDFs have no text-size option',
        !pdfLabels.some((label) => matches(label, '^Text size:')),
        pdfLabels.join(', '),
    );
    testCheck(
        'PDF menus keep the fixed-layout actions',
        pdfLabels.some((label) => matches(label, '^Reorder pages')),
        pdfLabels.join(', '),
    );
    testCheck(
        'PDF menus offer Fix PDF first',
        pdfLabels.length > 0 && equalsIgnoreCase(pdfLabels[0], 'Fix PDF'),
        pdfLabels.join(', '),
    );
    testCheck(
        'PDF menus end with Exit',
        pdfLabels.length > 0 && equalsIgnoreCase(pdfLabels[pdfLabels.length - 1], 'Exit'),
        pdfLabels.join(', '),
    );
}

function testSelection() {
    openDoc('notes.txt');
    waitForPattern('^PDFr plain text fixture$', 12000);

    const numbersBefore = getNumericNodes(getUiXml());
    testCheck('line numbers are shown before selecting', numbersBefore.length >= 8, `found ${numbersBefore.length}`);

    invokeAdb(['shell', 'input', 'motionevent', 'DOWN', 540, 900]);
    sleep(900);
    invokeAdb(['shell', 'input', 'motionevent', 'MOVE', 660, 900]);
    sleep(300);
    invokeAdb(['shell', 'input', 'motionevent', 'UP', 660, 900]);
    sleep(900);

    const partial = saveShot('selection-partial');
    const partialImage = new PdfrImage(partial);
    const gutter = Math.round(options.ScreenWidth * 0.13);
    const partialStats = partialImage.measureSelection(gutter);

    testCheck('long-press selects text natively', partialStats.count >= 1000, `selection pixels: ${partialStats.count}`);
    testCheck('selection handles are visible', partialStats.dark >= 100, `dark blue pixels: ${partialStats.dark}`);
    testCheck('the selection is not the whole document yet', partialStats.count < 50000, `selection pixels: ${partialStats.count}`);

    // The custom toolbar lives in the reader window, so uiautomator exposes its
    // buttons. It has Copy and Copy all, but deliberately no Select all.
    const touchMenu = getUiXml();
    testCheck('the touch menu shows Copy', Boolean(findNode('^Copy$', touchMenu)));
    testCheck('the touch menu shows Copy all', Boolean(findNode('^Copy all$', touchMenu)));
    testCheck('the touch menu has no Select all', !findNode('^Select all$', touchMenu));
    testCheck(
        'Copy is enabled while text is selected',
        testNodeEnabled(findNode('^Copy$', touchMenu)) === true,
    );

    // Copy keeps the manual-selection flow: it puts the selected text, not the
    // whole document, on the clipboard.
    tapPattern('^Copy$');
    sleep(4000);

    let dialog = openInfoDialog();
    let edits = getEditNodes(dialog);
    if (edits.length >= 1) {
        setEditText(edits[0], '');
        invokeAdb(['shell', 'input', 'keycombination', 113, 50]);
        sleep(900);
        const selected = attr(getEditNodes(getUiXml())[0], 'text');
        testCheck(
            'touch-menu Copy copies the selection',
            selected.length > 0 && !matches(selected, '^PDFr plain text fixture'),
            `copied ${selected.length} characters`,
        );
        closeInfoDialog('Cancel');
    }

    // Copy cleared the selection, so select again before testing Copy all.
    invokeAdb(['shell', 'input', 'motionevent', 'DOWN', 540, 900]);
    sleep(900);
    invokeAdb(['shell', 'input', 'motionevent', 'MOVE', 660, 900]);
    sleep(300);
    invokeAdb(['shell', 'input', 'motionevent', 'UP', 660, 900]);
    sleep(900);

    // Copy all ignores the partial selection and starts at the first line.
    tapPattern('^Copy all$');
    sleep(4000);

    dialog = openInfoDialog();
    edits = getEditNodes(dialog);
    if (edits.length >= 1) {
        setEditText(edits[0], '');
        invokeAdb(['shell', 'input', 'keycombination', 113, 50]);
        sleep(900);
        const pasted = attr(getEditNodes(getUiXml())[0], 'text');
        testCheck(
            'touch-menu Copy all starts at the first line',
            matches(pasted, '^PDFr plain text fixture'),
            pasted ? pasted.substring(0, Math.min(40, pasted.length)) : 'empty',
        );
        testCheck(
            'touch-menu Copy all ignores the selection',
            matches(pasted, 'quick brown fox'),
            `pasted ${pasted.length} characters`,
        );
        closeInfoDialog('Cancel');
    }

    invokeAdb([
        'shell', 'input', 'tap',
        Math.round(options.ScreenWidth * 0.5),
        Math.round(options.ScreenHeight * 0.72),
    ]);
    sleep(700);
    const cleared = saveShot('selection-cleared');
    const clearedImage = new PdfrImage(cleared);
    const clearedStats = clearedImage.measureSelection(gutter);
    testCheck('tapping clears the selection', clearedStats.count < 1000, `selection pixels: ${clearedStats.count}`);
    testCheck('the reader survives clearing the selection', Boolean(findNode('^PDFr plain text fixture$')));

    // "Copy all" must take the whole document from the model. The lazy list
    // only composes the rows near the viewport. A 40 pt text size keeps the
    // viewport to a handful of lines, while the pasted clipboard text still
    // reaches L030.
    openDoc('long.txt');
    waitForPattern('^L001$', 12000);
    setTextSize(40);
    closeInfoDialog('Apply');

    const labels = getMenuLabels();
    testCheck('the overflow menu offers Copy all', containsIgnoreCase(labels, 'Copy all'), labels.join(', '));
    testCheck(
        'Copy all is the first overflow entry',
        labels.length > 0 && equalsIgnoreCase(labels[0], 'Copy all'),
        labels.join(', '),
    );

    openOverflow();
    tapPattern('^Copy all$');
    // Android shows its own clipboard preview (and a share button) for a few
    // seconds. Both are blue-tinted, so wait for them to fade before measuring
    // whether the reader itself drew a selection. The preview can linger longer
    // than a fixed delay, so retry until the frame is clean.
    let copyStats = null;
    for (let attempt = 0; attempt < 5; attempt += 1) {
        sleep(2000);
        const copyShot = saveShot('copy-all');
        const copyImage = new PdfrImage(copyShot);
        copyStats = copyImage.measureSelection(gutter);
        if (copyStats.count < 500) break;
    }
    testCheck(
        'Copy all from the overflow menu draws no selection',
        copyStats.count < 500,
        `selection pixels: ${copyStats.count}`,
    );
    testCheck('Copy all closes the menu', !testMenuOpen());

    // Paste into the filename field to inspect the clipboard. That field keeps
    // 200 characters, which is about 40 five-character lines: far past what the
    // 40 pt viewport composed.
    dialog = openInfoDialog();
    edits = getEditNodes(dialog);
    if (edits.length >= 1) {
        setEditText(edits[0], '');
        invokeAdb(['shell', 'input', 'keycombination', 113, 50]);
        sleep(900);
        const pasted = attr(getEditNodes(getUiXml())[0], 'text');
        testCheck(
            'Copy all starts at the first line',
            matches(pasted, '^L001'),
            pasted ? pasted.substring(0, Math.min(40, pasted.length)) : 'empty',
        );
        testCheck(
            'Copy all includes lines beyond the viewport',
            matches(pasted, 'L030'),
            `pasted ${pasted.length} characters`,
        );
        closeInfoDialog('Cancel');
    }
}

function testMetadata() {
    openDoc('notes.txt');
    waitForPattern('^PDFr plain text fixture$', 12000);

    const dialog = openInfoDialog();
    const edits = getEditNodes(dialog);
    testCheck('the filename and extension are editable', edits.length >= 4, `found ${edits.length} fields`);
    if (edits.length >= 4) {
        testEqual('the filename starts as the document name', attr(edits[0], 'text'), 'notes');
        testEqual('the extension is separate', attr(edits[1], 'text'), 'txt');
        testMatch('the created date is ISO', attr(edits[2], 'text'), '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$');
        testMatch('the modified date is ISO', attr(edits[3], 'text'), '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$');
    }

    const visibleText = descendants(dialog, 'node')
        .filter((node) => attr(node, 'text'))
        .map((node) => attr(node, 'text'));
    testCheck(
        'encryption details are not shown',
        !visibleText.some((text) => matches(text, 'Encrypt|Password')),
    );
    testCheck(
        'the metadata section is offered',
        containsIgnoreCase(visibleText, 'Document metadata') &&
        visibleText.some((text) => matches(text, '^All document properties are listed')),
    );

    const dialogHeight = getDialogHeight(dialog);
    testCheck(
        'the info dialog is capped below the screen',
        dialogHeight > 200 && dialogHeight <= options.ScreenHeight * 0.76,
        `dialog height: ${dialogHeight}`,
    );

    if (edits.length >= 3) {
        setEditText(edits[0], 'draftname');
        dismissDialog();
        testCheck('dismissing keeps the draft', Boolean(waitForGone('^Apply$', 5000)));

        const reopened = openInfoDialog();
        testEqual('the draft survives reopening', attr(getEditNodes(reopened)[0], 'text'), 'draftname');

        closeInfoDialog('Cancel');
        const afterCancel = openInfoDialog();
        testEqual('Cancel discards the draft', attr(getEditNodes(afterCancel)[0], 'text'), 'notes');

        const current = getEditNodes(afterCancel);
        setEditText(current[0], 'editednotes');
        tapPattern('^Apply$');
        waitForGone('^Apply$', 12000);
        sleep(1200);
        testCheck('the reader reloads after Apply', Boolean(findNode('^PDFr plain text fixture$')));

        const applied = openInfoDialog();
        testEqual('Apply writes the edited filename', attr(getEditNodes(applied)[0], 'text'), 'editednotes');
        closeInfoDialog('Cancel');

        // Save as gets the edited name in the system picker.
        tapPattern('^Save as$');
        sleep(2000);
        const picker = getUiXml();
        const pickerNames = descendants(picker, 'node').filter((node) => (
            matches(attr(node, 'text'), 'editednotes') || matches(attr(node, 'content-desc'), 'editednotes')
        ));
        testCheck(
            'Save as uses the edited name',
            pickerNames.length > 0,
            pickerNames.length > 0 ? attr(pickerNames[0], 'text') : 'name not found',
        );

        for (let attempt = 0; attempt < 2; attempt += 1) {
            if (testPackageVisible('com.karako.pdfr')) break;
            invokeAdb(['shell', 'input', 'keyevent', 4]);
            sleep(700);
        }
        waitForPattern('^PDFr plain text fixture$', 8000);

        // Share makes a cache copy under the edited name before opening the
        // chooser. Checking the copy proves the chooser received that name.
        tapPattern('^Share document$');
        sleep(2000);
        const shareCopy = run(adb, [
            'shell',
            'run-as com.karako.pdfr find cache/documents -type f -name editednotes.txt',
        ], { allowFailure: true });
        const shareText = `${shareCopy.stdout}${shareCopy.stderr}`.trim();
        testCheck('Share uses the edited name', matches(shareText, 'editednotes\\.txt'), shareText);

        invokeAdb(['shell', 'input', 'keyevent', 4]);
        sleep(700);
        testCheck(
            'dismissing Share returns to the reader',
            Boolean(waitForPattern('^PDFr plain text fixture$', 8000)),
        );
    }
}

function testGestures() {
    enableRoot();

    openDoc('notes.txt');
    waitForPattern('^PDFr plain text fixture$', 12000);
    invokeZoomProbe('text-pinch', 'pinch-out');
    const textBefore = path.join(outDir, 'text-pinch-before.png');
    const textSettled = path.join(outDir, 'text-pinch-settled.png');
    const textDiff = PdfrImage.compare(textBefore, textSettled);
    testCheck(
        'a pinch does not zoom reflowable text',
        textDiff.comparable && textDiff.mean < 1.5 && textDiff.changed < 0.02,
        formatDiff(textDiff),
    );

    openDoc('sample.pdf');
    invokeZoomProbe('pdf-pinch', 'pinch-out', 0.20);
    const pdfBefore = path.join(outDir, 'pdf-pinch-before.png');
    const pdfSettled = path.join(outDir, 'pdf-pinch-settled.png');
    const pdfDiff = PdfrImage.compare(pdfBefore, pdfSettled);
    testCheck(
        'a pinch zooms a fixed-layout PDF',
        pdfDiff.comparable && (pdfDiff.mean > 1.0 || pdfDiff.changed > 0.03),
        formatDiff(pdfDiff),
    );
}

// ---------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------

function main() {
    adb = resolveAdb();
    outDir = path.resolve(options.OutDir || path.join(REPO_ROOT, 'build', 'reader-checks'));
    ensureDir(outDir);

    const selected = options.Section.map((section) => section.toLowerCase());
    for (const section of selected) {
        if (!ALLOWED_SECTIONS.includes(section)) {
            throw new Error(`Section '${section}' is not valid. Choose from: ${ALLOWED_SECTIONS.join(', ')}.`);
        }
    }
    const runAll = selected.includes('all');

    const state = adbCapture(['get-state']);
    if (state.trim() !== 'device') {
        throw new Error(`No Android device is connected. Start the emulator first.\n${state}`);
    }

    const packagePath = adbCapture(['shell', 'pm path com.karako.pdfr']);
    if (!/package:/i.test(packagePath)) {
        throw new Error('com.karako.pdfr is not installed. Run tools\\install-debug.bat first.');
    }

    // build/test-fixtures is deliberately disposable: `gradlew clean` removes
    // it. Recreate the few documents the checks open instead of failing later
    // with a confusing "fixture not found" error.
    ensureFixtures();

    invokeAdb(['shell', 'svc power stayon true']);
    invokeAdb(['shell', 'input', 'keyevent', 224]);
    invokeAdb(['shell', 'wm dismiss-keyguard']);

    const sections = [
        ['empty', 'No document open', testEmptyState],
        ['text', 'Plain text settings', testPlainTextSettings],
        ['selection', 'Selection', testSelection],
        ['metadata', 'Metadata editor', testMetadata],
        ['gestures', 'Gestures', testGestures],
        ['exit', 'Exit', testExit],
    ];
    for (const [key, title, body] of sections) {
        if (runAll ? DEFAULT_SECTIONS.includes(key) : selected.includes(key)) {
            testSection(title, body);
        }
    }

    const report = path.join(outDir, 'results.txt');
    const lines = checks.map((check) => (
        `${check.ok ? 'PASS' : 'FAIL'}  ${check.section}  ${check.name}  ${check.detail}`
    ));
    fs.writeFileSync(report, lines.length > 0 ? `${lines.join(os.EOL)}${os.EOL}` : '', 'utf8');

    const failed = checks.filter((check) => !check.ok);
    const passed = checks.length - failed.length;

    say('');
    say(`=== ${passed} passed, ${failed.length} failed ===`);
    say(`[PDFr] Report: ${report}`);

    if (!options.KeepOpen) {
        invokeAdb(['shell', 'am force-stop com.karako.pdfr']);
    }

    return failed.length > 0 ? 1 : 0;
}

if (require.main === module) {
    runMain(main);
}

module.exports = { PdfrImage, parseXml, mergeAppWindows };
