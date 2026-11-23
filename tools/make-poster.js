#!/usr/bin/env node
'use strict';

/**
 * Compose 3-4 PDFr screenshots into a single promotional poster.
 *
 * Reads the screenshots captured by tools\capture-promo.js (or any PNGs you
 * pass with -Images) and writes a shareable poster with a dark gradient
 * background, a title, rounded screenshot cards and a footer. Only Node.js
 * built-ins and the tools\lib helpers are used, so no ImageMagick or ffmpeg
 * install is needed.
 *
 *     node tools\make-poster.js
 *     node tools\make-poster.js -OutDir build\promo -Images shot1.png,shot2.png,shot3.png
 *     node tools\make-poster.js -OutDir build\promo -Columns 1 -Width 1000
 */

const fs = require('node:fs');
const path = require('node:path');
const { isFile, outDir, parseCli, runMain, say } = require('./lib/common');
const { readPng, writePng } = require('./lib/png');
const {
    createImage,
    drawImageInRoundRect,
    drawText,
    fillLinearGradient,
    fillRect,
    fillRoundRect,
    measureText,
    strokeRoundRect,
} = require('./lib/raster');

const PREFERRED_IMAGES = [
    '02-pdf-cover.png',
    '03-pdf-colour.png',
    '04-overflow.png',
    '06-epub.png',
];

/** Copies a #RRGGBB colour with a new alpha byte, like Color.FromArgb(alpha, color). */
function withAlpha(color, alpha) {
    let text = String(color).trim().replace(/^#/, '');
    if (text.length === 3) text = text.split('').map((part) => part + part).join('');
    if (text.length === 6) text += (alpha | 0).toString(16).padStart(2, '0');
    return `#${text}`;
}

function resolveImageList(outDirPath, images, outputName) {
    if (images.length > 0) {
        return images.map((image) => path.resolve(outDirPath, image));
    }

    const chosen = [];
    for (const name of PREFERRED_IMAGES) {
        const candidate = path.join(outDirPath, name);
        if (isFile(candidate)) chosen.push(candidate);
    }

    if (chosen.length < 3) {
        return fs.readdirSync(outDirPath)
            .filter((name) => name.toLowerCase().endsWith('.png'))
            .filter((name) => name.toLowerCase() !== outputName.toLowerCase())
            .sort((a, b) => a.localeCompare(b))
            .slice(0, 4)
            .map((name) => path.join(outDirPath, name));
    }

    return chosen.slice(0, 4);
}

function main(argv) {
    const options = parseCli(argv, {
        OutDir: { type: 'string', default: outDir('promo') },
        Images: { type: 'list', default: [] },
        OutputName: { type: 'string', default: 'poster.png' },
        Title: { type: 'string', default: 'PDFr' },
        Subtitle: { type: 'string', default: 'Read anything.' },
        Width: { type: 'int', default: 1600 },
        Columns: { type: 'int', default: 2 },
        Background: { type: 'string', default: '#0F1720' },
        BackgroundEnd: { type: 'string', default: '#22384D' },
        Accent: { type: 'string', default: '#E0A526' },
        Foreground: { type: 'string', default: '#F5F1E8' },
    });

    const targetDir = path.resolve(options.OutDir);
    if (!fs.existsSync(targetDir)) {
        throw new Error(`Output folder not found: ${targetDir}`);
    }

    const paths = resolveImageList(targetDir, options.Images, options.OutputName);
    if (paths.length < 1) {
        throw new Error(`No screenshots found in ${targetDir}. Run tools\\capture-promo.js first.`);
    }

    const loaded = paths.map((file) => {
        if (!isFile(file)) throw new Error(`Screenshot not found: ${file}`);
        return readPng(file);
    });

    const padding = 72;
    const gap = 28;
    const headerHeight = 300;
    const footerHeight = 120;
    const cellWidth = Math.trunc(
        (options.Width - padding * 2 - gap * (options.Columns - 1)) / options.Columns,
    );
    const aspect = loaded[0].width / loaded[0].height;
    const cellHeight = Math.trunc(cellWidth / aspect);
    const rows = Math.ceil(loaded.length / options.Columns);
    const height = headerHeight + rows * cellHeight + (rows - 1) * gap + footerHeight;

    const image = createImage(options.Width, height);

    // 90-degree GDI+ gradient: straight down the canvas.
    fillLinearGradient(
        image, 0, 0, 0, height,
        options.Background, options.BackgroundEnd,
    );

    const muted = withAlpha(options.Foreground, 190);

    // 96 px title and 34 px subtitle in the old pixel font become scales 14
    // and 5 in the built-in 7 px-tall font.
    const titleScale = 14;
    const titleHeight = 7 * titleScale;
    drawText(image, options.Title, padding, 72, titleScale, options.Foreground, { bold: true });
    fillRect(image, padding, 72 + titleHeight + 6, 160, 8, options.Accent);
    drawText(image, options.Subtitle, padding, 72 + titleHeight + 34, 5, muted);

    for (let index = 0; index < loaded.length; index += 1) {
        const source = loaded[index];
        const row = Math.floor(index / options.Columns);
        const column = index % options.Columns;
        const itemsInRow = Math.min(options.Columns, loaded.length - row * options.Columns);
        const rowWidth = itemsInRow * cellWidth + (itemsInRow - 1) * gap;
        const rowStart = Math.trunc((options.Width - rowWidth) / 2);
        const x = rowStart + column * (cellWidth + gap);
        const y = headerHeight + row * (cellHeight + gap);

        fillRoundRect(image, x + 6, y + 14, cellWidth, cellHeight, 28, [0, 0, 0, 90]);
        fillRoundRect(image, x, y, cellWidth, cellHeight, 28, '#121C26');

        const scale = Math.min(cellWidth / source.width, cellHeight / source.height);
        const drawWidth = Math.trunc(source.width * scale);
        const drawHeight = Math.trunc(source.height * scale);
        const drawX = Math.trunc(x + (cellWidth - drawWidth) / 2);
        const drawY = Math.trunc(y + (cellHeight - drawHeight) / 2);
        drawImageInRoundRect(image, source, drawX, drawY, drawWidth, drawHeight, 28);

        strokeRoundRect(image, x, y, cellWidth, cellHeight, 28, 3, options.Accent);
    }

    const footerY = height - footerHeight + 28;
    const footerText = 'PDFr  |  MuPDF-powered  |  no ads  |  no tracking';
    const footer = 'github.com/eladkarako/pdfr';
    // The bitmap font is wider than Segoe UI. Keep the 22 px-equivalent
    // scale when both footer strings fit, and step down for narrow canvases.
    let footerScale = 3;
    while (
        footerScale > 1
        && padding * 2 + measureText(footerText, footerScale) + 24 + measureText(footer, footerScale) > options.Width
    ) {
        footerScale -= 1;
    }
    drawText(
        image,
        footerText,
        padding, footerY, footerScale, muted,
    );
    const footerWidth = measureText(footer, footerScale);
    drawText(
        image, footer,
        options.Width - padding - footerWidth, footerY,
        footerScale, options.Accent,
    );

    const outputPath = path.resolve(targetDir, options.OutputName);
    writePng(outputPath, image);
    say(`[PDFr] Poster written to ${outputPath}`);
}

runMain(() => main(process.argv.slice(2)));
