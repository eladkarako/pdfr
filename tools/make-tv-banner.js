#!/usr/bin/env node
'use strict';

/**
 * Draws the Android TV banner (320x180) used by android:banner.
 *
 * Uses the project's own PNG and raster helpers instead of System.Drawing, so
 * it runs with Node.js alone. The output goes to
 * app\src\main\res\drawable-nodpi\tv_banner.png by default.
 *
 *     node tools\make-tv-banner.js
 *     node tools\make-tv-banner.js -OutFile C:\tmp\banner.png
 */

const path = require('node:path');
const { REPO_ROOT, ensureDir, parseCli, runMain, say } = require('./lib/common');
const { writePng } = require('./lib/png');
const {
    createImage,
    drawText,
    fillLinearGradient,
    fillRect,
} = require('./lib/raster');

const WIDTH = 320;
const HEIGHT = 180;

function main(argv) {
    const options = parseCli(argv, {
        OutFile: {
            type: 'string',
            default: path.join(REPO_ROOT, 'app', 'src', 'main', 'res', 'drawable-nodpi', 'tv_banner.png'),
        },
    });

    const outFile = path.resolve(options.OutFile);
    ensureDir(path.dirname(outFile));

    const image = createImage(WIDTH, HEIGHT);

    // Deep blue gradient, the same family as the adaptive icon background.
    // GDI+ used a 45-degree brush, which is the lower-left to upper-right
    // diagonal of the rectangle.
    fillLinearGradient(image, 0, HEIGHT, WIDTH, 0, '#102A43', '#265C8C');

    // A simple document sheet on the left.
    fillRect(image, 38, 38, 78, 104, '#F5F8FC');
    // Folded corner: fill the triangle a scanline at a time.
    for (let row = 0; row < 24; row += 1) {
        fillRect(image, 92, 38 + row, row, 1, '#B9CADB');
    }
    // Four ruled lines, 4 px thick like the old pen.
    for (const y of [82, 96, 110, 124]) {
        fillRect(image, 52, y - 2, 48, 4, '#7896B4');
    }

    // Title and subtitle. The built-in font is 7 px tall per scale unit, so
    // scale 6 is 42 px and scale 2 is 14 px.
    drawText(image, 'PDFr', 138, 55, 6, '#FFFFFF', { bold: true });
    drawText(image, 'Read anything', 141, 108, 2, '#C6DEEE');

    writePng(outFile, image);
    say(`Wrote ${outFile} (${WIDTH} x ${HEIGHT})`);
}

runMain(() => main(process.argv.slice(2)));
