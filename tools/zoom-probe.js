#!/usr/bin/env node
'use strict';

/**
 * Records a screenshot sequence while pinching or panning, for checking that
 * zoom resizes the page live and settles without a jump.
 *
 * Node.js port of the old zoom-probe.ps1. Windows-first, built-ins only. The
 * pinch and pan gestures use Linux multitouch events, because `adb shell input`
 * cannot press two fingers. They need `adb root`; on an emulator that is
 * already rooted, pass -SkipRoot so adbd does not restart and resize the
 * viewport between the "before" shot and the gesture.
 *
 * Every run writes before/down/mid/late/settled PNGs to build\zoom-probe\. The
 * late and settled shots are compared over the reading area (the toolbar and
 * the fading page badge are ignored): a stable score means the deferred render
 * or reflow landed at exactly the live size, while a large score points at a
 * settle jump. PNGs are decoded with tools\lib\png.js, so no System.Drawing or
 * external packages are involved.
 *
 * Coordinates default to the pdfr_full AVD (1080x2340). Override
 * -ScreenWidth/-ScreenHeight and -EventDevice (find it with
 * `adb shell getevent -lp`, the device whose name contains multi_touch).
 *
 *     node tools\zoom-probe.js -Action pinch-out
 *     node tools\zoom-probe.js -Action pinch-out -CenterX 0.3 -CenterY 0.17 -SkipRoot
 *     node tools\zoom-probe.js -Action pan -StartY 1700 -EndY 900
 */

const fs = require('node:fs');
const path = require('node:path');
const {
    ensureDir,
    outDir,
    parseCli,
    resolveAdb,
    run,
    runMain,
    sleep,
    step,
} = require('./lib/common');
const { readPng } = require('./lib/png');

const ACTIONS = ['pinch-out', 'pinch-in', 'pan', 'double-tap'];

const SPEC = {
    Action: { type: 'string', default: 'pinch-out' },
    EventDevice: { type: 'string', default: '/dev/input/event2' },
    ScreenWidth: { type: 'int', default: 1080 },
    ScreenHeight: { type: 'int', default: 2340 },
    // Fraction of the screen height where the pinch centroid sits. Use a spot
    // with recognisable content, for example 0.17 for the title of a PDF page.
    CenterY: { type: 'double', default: 0.45 },
    // Fraction of the screen width where the pinch centroid sits. Move it away
    // from the centre to check that the zoom stays anchored to the midpoint
    // rather than to the page's left edge or the first finger.
    CenterX: { type: 'double', default: 0.5 },
    StartGap: { type: 'int', default: 280 },
    EndGap: { type: 'int', default: 620 },
    Gap: { type: 'int', default: 420 },
    StartY: { type: 'int', default: 1700 },
    EndY: { type: 'int', default: 900 },
    Steps: { type: 'int', default: 8 },
    SettleMs: { type: 'int', default: 700 },
    // Time between the last finger move and the "late" screenshot. The input
    // event still has to travel through adb, so capturing immediately raced the
    // final zoom and made the probe report a jump that never happened.
    LateDelayMs: { type: 'int', default: 400 },
    OutDir: { type: 'string', default: '' },
    Prefix: { type: 'string', default: 'zoom' },
    SkipRoot: { type: 'bool', default: false },
};

/**
 * PowerShell casts doubles to int with banker's rounding; keep that so the
 * synthesised touch coordinates match the old script exactly.
 */
function toInt(value) {
    const floor = Math.floor(value);
    const difference = value - floor;
    if (difference > 0.5) return floor + 1;
    if (difference < 0.5) return floor;
    return floor % 2 === 0 ? floor : floor + 1;
}

function requireChoice(value, allowed, label) {
    const lower = String(value).toLowerCase();
    if (!allowed.some((candidate) => candidate.toLowerCase() === lower)) {
        throw new Error(`Unknown ${label} '${value}'. Choose one of: ${allowed.join(', ')}.`);
    }
    return lower;
}

/**
 * Compares only the reading area: the toolbar and the fading page badge must
 * not count as movement. A real settle jump shifts a large fraction of the
 * page, while a sharper re-render only changes edge pixels by a small amount.
 */
function getSettleDifference(late, settled) {
    if (late.width !== settled.width || late.height !== settled.height) {
        return { mean: 255.0, changed: 1.0, comparable: false };
    }

    const width = late.width;
    const height = late.height;
    const left = 0;
    const right = width;
    const top = toInt(height * 0.14);
    const bottom = toInt(height * 0.90);
    const stride = 4;

    let total = 0.0;
    let count = 0;
    let changed = 0;
    for (let y = top; y < bottom; y += stride) {
        const row = y * width;
        for (let x = left; x < right; x += stride) {
            const offset = (row + x) * 4;
            const difference = (
                Math.abs(late.data[offset] - settled.data[offset])
                + Math.abs(late.data[offset + 1] - settled.data[offset + 1])
                + Math.abs(late.data[offset + 2] - settled.data[offset + 2])
            ) / 3.0;
            total += difference;
            count += 1;
            if (difference > 24) changed += 1;
        }
    }

    return {
        mean: count > 0 ? total / count : 0.0,
        changed: count > 0 ? changed / count : 0.0,
        comparable: true,
    };
}

function main() {
    const cli = parseCli(process.argv.slice(2), SPEC);
    const action = requireChoice(cli.Action, ACTIONS, 'action');

    const adb = resolveAdb();
    const outputDirectory = cli.OutDir || outDir('zoom-probe');
    ensureDir(outputDirectory);

    function invokeAdb(args, options = {}) {
        const result = run(adb, args, { allowFailure: options.allowFailure === true });
        if (!options.quiet && result.stdout) process.stdout.write(result.stdout);
        if (result.stderr) process.stderr.write(result.stderr);
        return result;
    }

    function saveShot(name) {
        const remote = `/sdcard/pdfr-${cli.Prefix}-${name}.png`;
        const local = path.join(outputDirectory, `${cli.Prefix}-${name}.png`);
        invokeAdb(['shell', `screencap -p ${remote}`]);
        invokeAdb(['pull', remote, local], { quiet: true });
        // The remote file is only a scratch copy; a failed cleanup must not
        // fail an otherwise good run.
        invokeAdb(['shell', 'rm', remote], { quiet: true, allowFailure: true });
        step(local);
    }

    function ensureRoot() {
        if (cli.SkipRoot) {
            const identity = run(adb, ['shell', 'id'], { allowFailure: true }).stdout.trim();
            if (!/uid=0/.test(identity)) {
                throw new Error("Raw multitouch needs root. Run 'adb root' first, or omit -SkipRoot.");
            }
            return;
        }

        run(adb, ['root'], { allowFailure: true });
        sleep(1000);
        run(adb, ['wait-for-device'], { allowFailure: true });

        const identity = run(adb, ['shell', 'id'], { allowFailure: true }).stdout.trim();
        if (!/uid=0/.test(identity)) {
            throw new Error('Raw multitouch needs adb root. Use an emulator, or test the pinch by hand.');
        }
    }

    function convertToRaw(value, screenSize) {
        return toInt((value * 32767.0) / screenSize);
    }

    function sendEvents(events) {
        invokeAdb(['shell', events.join('; ')]);
    }

    const device = cli.EventDevice;
    const centerX = toInt(cli.ScreenWidth * cli.CenterX);
    const centerY = toInt(cli.ScreenHeight * cli.CenterY);

    function sendDown(leftX, rightX, y) {
        const rawY = convertToRaw(y, cli.ScreenHeight);
        const events = [
            `sendevent ${device} 3 47 0`,
            `sendevent ${device} 3 57 11`,
            `sendevent ${device} 3 53 ${convertToRaw(leftX, cli.ScreenWidth)}`,
            `sendevent ${device} 3 54 ${rawY}`,
            `sendevent ${device} 3 58 512`,
            `sendevent ${device} 3 48 100`,
            `sendevent ${device} 3 47 1`,
            `sendevent ${device} 3 57 12`,
            `sendevent ${device} 3 53 ${convertToRaw(rightX, cli.ScreenWidth)}`,
            `sendevent ${device} 3 54 ${rawY}`,
            `sendevent ${device} 3 58 512`,
            `sendevent ${device} 3 48 100`,
            `sendevent ${device} 1 330 1`,
            `sendevent ${device} 0 0 0`,
        ];
        sendEvents(events);
    }

    function sendMove(leftX, rightX, y) {
        const rawY = convertToRaw(y, cli.ScreenHeight);
        const events = [
            `sendevent ${device} 3 47 0`,
            `sendevent ${device} 3 53 ${convertToRaw(leftX, cli.ScreenWidth)}`,
            `sendevent ${device} 3 54 ${rawY}`,
            `sendevent ${device} 3 47 1`,
            `sendevent ${device} 3 53 ${convertToRaw(rightX, cli.ScreenWidth)}`,
            `sendevent ${device} 3 54 ${rawY}`,
            `sendevent ${device} 0 0 0`,
        ];
        sendEvents(events);
    }

    function sendUp() {
        const events = [
            `sendevent ${device} 3 47 0`,
            `sendevent ${device} 3 57 -1`,
            `sendevent ${device} 3 47 1`,
            `sendevent ${device} 3 57 -1`,
            `sendevent ${device} 1 330 0`,
            `sendevent ${device} 0 0 0`,
        ];
        sendEvents(events);
    }

    function sendPinch(label) {
        step(`${label} around y=${centerY}...`);
        const startLeft = centerX - toInt(cli.StartGap / 2);
        const startRight = centerX + toInt(cli.StartGap / 2);
        const endLeft = centerX - toInt(cli.EndGap / 2);
        const endRight = centerX + toInt(cli.EndGap / 2);

        sendDown(startLeft, startRight, centerY);
        sleep(120);
        saveShot('down');

        const midGap = toInt(cli.StartGap + (cli.EndGap - cli.StartGap) * 0.45);
        sendMove(centerX - toInt(midGap / 2), centerX + toInt(midGap / 2), centerY);
        sleep(150);
        saveShot('mid');

        sendMove(endLeft, endRight, centerY);
        sleep(cli.LateDelayMs);
        saveShot('late');

        sendUp();
    }

    ensureRoot();
    saveShot('before');
    sleep(150);

    switch (action) {
        case 'pinch-out':
            sendPinch('Pinching out (zoom in)');
            break;
        case 'pinch-in':
            sendPinch('Pinching in (zoom out)');
            break;
        case 'pan': {
            step(`Two-finger pan from y=${cli.StartY} to y=${cli.EndY}...`);
            const leftX = centerX - toInt(cli.Gap / 2);
            const rightX = centerX + toInt(cli.Gap / 2);

            sendDown(leftX, rightX, cli.StartY);
            sleep(120);
            saveShot('down');

            const moveStep = (cli.EndY - cli.StartY) / cli.Steps;
            const half = toInt(cli.Steps / 2);
            for (let index = 1; index <= half; index += 1) {
                sendMove(leftX, rightX, toInt(cli.StartY + moveStep * index));
                sleep(25);
            }
            sleep(100);
            saveShot('mid');

            for (let index = half + 1; index <= cli.Steps; index += 1) {
                sendMove(leftX, rightX, toInt(cli.StartY + moveStep * index));
                sleep(25);
            }
            sendMove(leftX, rightX, cli.EndY);
            sleep(cli.LateDelayMs);
            saveShot('late');

            sendUp();
            break;
        }
        case 'double-tap':
            step('Double-tapping the centre of the screen...');
            invokeAdb(['shell', 'input', 'tap', String(centerX), String(centerY)]);
            sleep(80);
            invokeAdb(['shell', 'input', 'tap', String(centerX), String(centerY)]);
            break;
        default:
            throw new Error(`Unknown action '${action}'.`);
    }

    sleep(cli.SettleMs);
    saveShot('settled');

    const latePath = path.join(outputDirectory, `${cli.Prefix}-late.png`);
    const settledPath = path.join(outputDirectory, `${cli.Prefix}-settled.png`);

    if (fs.existsSync(latePath) && fs.existsSync(settledPath)) {
        const difference = getSettleDifference(readPng(latePath), readPng(settledPath));
        const mean = difference.mean.toFixed(2);
        const changed = `${(difference.changed * 100).toFixed(1)}%`;
        if (!difference.comparable) {
            step('late and settled have different sizes: compare them for a jump.');
        } else if (difference.mean < 1.5 && difference.changed < 0.02) {
            step(`late and settled are stable: no settle jump (mean ${mean}, changed ${changed}).`);
        } else if (difference.mean < 6.0 && difference.changed < 0.15) {
            step('late and settled differ slightly, likely a sharper '
                + `re-render rather than a jump (mean ${mean}, changed ${changed}).`);
        } else {
            step('late and settled differ substantially: compare them '
                + `for a size or position jump (mean ${mean}, changed ${changed}).`);
        }
    }

    step(`Screenshots in ${outputDirectory}`);
    return 0;
}

if (require.main === module) runMain(main);

module.exports = {
    getSettleDifference,
    toInt,
};
