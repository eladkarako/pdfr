#!/usr/bin/env node
'use strict';

/**
 * Sends reader gestures to the connected Android device.
 *
 * The pinch gestures use Linux multitouch events, which is the only way adb
 * can press two fingers at once. They need `adb root`, which works on an
 * emulator (and on debuggable engineering builds), not on a retail phone.
 *
 * Coordinates default to a 1080x2340 phone/AVD. Override -ScreenWidth and
 * -ScreenHeight for another device. The multitouch device defaults to the
 * emulator's /dev/input/event2; use `adb shell getevent -lp` to find another.
 *
 *     node tools\gestures.js pinch-out
 *     node tools\gestures.js -Gesture double-tap
 *     node tools\gestures.js -Gesture pinch-in -ScreenWidth 1080 -ScreenHeight 2340
 *     tools\gestures.bat select-word
 *
 * Uses only Node.js built-ins (plus tools/lib/common.js).
 */

const {
    parseCli,
    resolveAdb,
    run,
    runMain,
    say,
    sleep,
    step,
} = require('./lib/common');

const GESTURES = ['pinch-out', 'pinch-in', 'double-tap', 'select-word', 'swipe-up'];

const adb = resolveAdb();

function invokeAdb(args) {
    const result = run(adb, args, { allowFailure: true });
    if (result.status !== 0) throw new Error(`adb failed: ${args.join(' ')}`);
    return result;
}

function getProp(name) {
    return run(adb, ['shell', 'getprop', name], { allowFailure: true }).stdout.trim();
}

function ensureRoot() {
    run(adb, ['root'], { allowFailure: true });
    sleep(1000);
    run(adb, ['wait-for-device'], { allowFailure: true });

    const identity = run(adb, ['shell', 'id'], { allowFailure: true }).stdout.trim();
    if (!/uid=0/.test(identity)) {
        throw new Error("Raw multitouch needs adb root. Run 'adb root' on an emulator, or test the pinch by hand.");
    }
}

/** PowerShell's [Math]::Round rounds halves to even; keep the same values. */
function roundHalfEven(value) {
    const floor = Math.floor(value);
    const fraction = value - floor;
    if (fraction > 0.5) return floor + 1;
    if (fraction < 0.5) return floor;
    return floor % 2 === 0 ? floor : floor + 1;
}

function convertToRaw(value, screenSize) {
    return roundHalfEven((value * 32767.0) / screenSize);
}

function sendEvents(events) {
    invokeAdb(['shell', events.join('; ')]);
}

function invokePinch(screenWidth, screenHeight, eventDevice, steps, startGap, endGap) {
    ensureRoot();

    const centerX = Math.trunc(screenWidth / 2);
    const centerY = Math.trunc(screenHeight / 2);

    const startLeft = centerX - Math.trunc(startGap / 2);
    const startRight = centerX + Math.trunc(startGap / 2);
    const endLeft = centerX - Math.trunc(endGap / 2);
    const endRight = centerX + Math.trunc(endGap / 2);

    const rawY = convertToRaw(centerY, screenHeight);
    const events = [];
    const device = eventDevice;

    events.push(`sendevent ${device} 3 47 0`);
    events.push(`sendevent ${device} 3 57 11`);
    events.push(`sendevent ${device} 3 53 ${convertToRaw(startLeft, screenWidth)}`);
    events.push(`sendevent ${device} 3 54 ${rawY}`);
    events.push(`sendevent ${device} 3 58 512`);
    events.push(`sendevent ${device} 3 48 100`);
    events.push(`sendevent ${device} 3 47 1`);
    events.push(`sendevent ${device} 3 57 12`);
    events.push(`sendevent ${device} 3 53 ${convertToRaw(startRight, screenWidth)}`);
    events.push(`sendevent ${device} 3 54 ${rawY}`);
    events.push(`sendevent ${device} 3 58 512`);
    events.push(`sendevent ${device} 3 48 100`);
    events.push(`sendevent ${device} 1 330 1`);
    events.push(`sendevent ${device} 0 0 0`);

    for (let stepIndex = 1; stepIndex <= steps; stepIndex += 1) {
        const fraction = stepIndex / steps;
        const left = Math.trunc(startLeft + (endLeft - startLeft) * fraction);
        const right = Math.trunc(startRight + (endRight - startRight) * fraction);

        events.push(`sendevent ${device} 3 47 0`);
        events.push(`sendevent ${device} 3 53 ${convertToRaw(left, screenWidth)}`);
        events.push(`sendevent ${device} 3 54 ${rawY}`);
        events.push(`sendevent ${device} 3 47 1`);
        events.push(`sendevent ${device} 3 53 ${convertToRaw(right, screenWidth)}`);
        events.push(`sendevent ${device} 3 54 ${rawY}`);
        events.push(`sendevent ${device} 0 0 0`);
        events.push('sleep 0.03');
    }

    events.push(`sendevent ${device} 3 47 0`);
    events.push(`sendevent ${device} 3 57 -1`);
    events.push(`sendevent ${device} 3 47 1`);
    events.push(`sendevent ${device} 3 57 -1`);
    events.push(`sendevent ${device} 1 330 0`);
    events.push(`sendevent ${device} 0 0 0`);

    sendEvents(events);
}

runMain(async () => {
    const options = parseCli(process.argv.slice(2), {
        Gesture: { type: 'string', default: 'pinch-out' },
        EventDevice: { type: 'string', default: '/dev/input/event2' },
        ScreenWidth: { type: 'int', default: 1080 },
        ScreenHeight: { type: 'int', default: 2340 },
        Steps: { type: 'int', default: 12 },
    });

    // The gesture can be positional (`gestures.bat pinch-out`) or named.
    const gesture = options._positional[0] !== undefined
        ? options._positional[0]
        : options.Gesture;
    if (!GESTURES.includes(gesture)) {
        throw new Error(`Invalid gesture '${gesture}'. Use one of: ${GESTURES.join(', ')}.`);
    }

    const { EventDevice: eventDevice, ScreenWidth: screenWidth, ScreenHeight: screenHeight, Steps: steps } = options;

    const abi = getProp('ro.product.cpu.abi');
    if (!abi) {
        throw new Error('No device connected. Start the emulator first.');
    }

    const centerX = Math.trunc(screenWidth / 2);
    const centerY = Math.trunc(screenHeight / 2);

    switch (gesture) {
        case 'pinch-out':
            step(`Pinching out (zoom in) on ${screenWidth} x ${screenHeight}...`);
            invokePinch(screenWidth, screenHeight, eventDevice, steps, 280, 680);
            break;
        case 'pinch-in':
            step(`Pinching in (zoom out) on ${screenWidth} x ${screenHeight}...`);
            invokePinch(screenWidth, screenHeight, eventDevice, steps, 780, 120);
            break;
        case 'double-tap':
            step('Double-tapping the centre of the screen...');
            invokeAdb(['shell', 'input', 'tap', String(centerX), String(centerY)]);
            sleep(80);
            invokeAdb(['shell', 'input', 'tap', String(centerX), String(centerY)]);
            break;
        case 'select-word': {
            step('Long-pressing and dragging to select text...');
            const selectY = Math.trunc(screenHeight * 0.55);
            invokeAdb(['shell', 'input', 'motionevent', 'DOWN', String(centerX), String(selectY)]);
            sleep(900);
            invokeAdb(['shell', 'input', 'motionevent', 'MOVE', String(centerX + 120), String(selectY)]);
            sleep(300);
            invokeAdb(['shell', 'input', 'motionevent', 'UP', String(centerX + 120), String(selectY)]);
            break;
        }
        case 'swipe-up':
            step('Swiping up to scroll...');
            invokeAdb(['shell', 'input', 'swipe', String(centerX), '1800', String(centerX), '600', '500']);
            break;
        default:
            break;
    }

    step('Gesture sent.');
    return 0;
});
