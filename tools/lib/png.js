'use strict';

/**
 * Minimal PNG reader and writer built on Node's zlib.
 *
 * Android screenshots and the project's generated images are ordinary 8-bit
 * PNGs, so this covers the non-interlaced colour types they use: grey, RGB,
 * palette, grey+alpha and RGBA. Everything is expanded to RGBA, which keeps
 * the drawing helpers simple. No external packages are needed.
 *
 *     const { readPng, writePng } = require('./lib/png');
 *     const image = readPng('shot.png'); // { width, height, data }
 */

const fs = require('node:fs');
const zlib = require('node:zlib');

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function crc32(buffer) {
    let crc = 0xffffffff;
    for (const byte of buffer) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit += 1) {
            crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
        }
    }
    return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
    const name = Buffer.from(type, 'ascii');
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length, 0);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([name, data])), 0);
    return Buffer.concat([length, name, data, crc]);
}

function paeth(a, b, c) {
    const p = a + b - c;
    const pa = Math.abs(p - a);
    const pb = Math.abs(p - b);
    const pc = Math.abs(p - c);
    if (pa <= pb && pa <= pc) return a;
    if (pb <= pc) return b;
    return c;
}

function readPng(file) {
    const buffer = Buffer.isBuffer(file) ? file : fs.readFileSync(file);
    if (!buffer.subarray(0, 8).equals(SIGNATURE)) {
        throw new Error('Not a PNG file');
    }

    let offset = 8;
    let width = 0;
    let height = 0;
    let bitDepth = 0;
    let colorType = 0;
    let interlace = 0;
    let palette = null;
    let transparency = null;
    const idat = [];

    while (offset + 8 <= buffer.length) {
        const length = buffer.readUInt32BE(offset);
        const type = buffer.toString('ascii', offset + 4, offset + 8);
        const data = buffer.subarray(offset + 8, offset + 8 + length);
        offset += 12 + length;

        if (type === 'IHDR') {
            width = data.readUInt32BE(0);
            height = data.readUInt32BE(4);
            bitDepth = data[8];
            colorType = data[9];
            interlace = data[12];
        } else if (type === 'PLTE') {
            palette = data;
        } else if (type === 'tRNS') {
            transparency = data;
        } else if (type === 'IDAT') {
            idat.push(data);
        } else if (type === 'IEND') {
            break;
        }
    }

    if (!width || !height) throw new Error('PNG has no IHDR');
    if (bitDepth !== 8) throw new Error(`Unsupported PNG bit depth: ${bitDepth}`);
    if (interlace !== 0) throw new Error('Interlaced PNGs are not supported');

    const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
    if (!channels) throw new Error(`Unsupported PNG colour type: ${colorType}`);

    const raw = zlib.inflateSync(Buffer.concat(idat));
    const stride = width * channels;
    const pixels = Buffer.alloc(stride * height);
    let position = 0;

    for (let y = 0; y < height; y += 1) {
        const filter = raw[position];
        position += 1;
        const row = raw.subarray(position, position + stride);
        position += stride;
        const target = pixels.subarray(y * stride, (y + 1) * stride);
        const previous = y > 0 ? pixels.subarray((y - 1) * stride, y * stride) : null;

        for (let x = 0; x < stride; x += 1) {
            const left = x >= channels ? target[x - channels] : 0;
            const up = previous ? previous[x] : 0;
            const upLeft = previous && x >= channels ? previous[x - channels] : 0;
            let value = row[x];
            switch (filter) {
                case 0: break;
                case 1: value += left; break;
                case 2: value += up; break;
                case 3: value += Math.floor((left + up) / 2); break;
                case 4: value += paeth(left, up, upLeft); break;
                default: throw new Error(`Unknown PNG filter: ${filter}`);
            }
            target[x] = value & 0xff;
        }
    }

    const data = Buffer.alloc(width * height * 4);
    for (let pixel = 0; pixel < width * height; pixel += 1) {
        const source = pixel * channels;
        const target = pixel * 4;
        if (colorType === 0) {
            const grey = pixels[source];
            data[target] = grey;
            data[target + 1] = grey;
            data[target + 2] = grey;
            data[target + 3] = 255;
        } else if (colorType === 2) {
            data[target] = pixels[source];
            data[target + 1] = pixels[source + 1];
            data[target + 2] = pixels[source + 2];
            data[target + 3] = 255;
        } else if (colorType === 3) {
            const index = pixels[source];
            const base = index * 3;
            data[target] = palette ? palette[base] : index;
            data[target + 1] = palette ? palette[base + 1] : index;
            data[target + 2] = palette ? palette[base + 2] : index;
            data[target + 3] = transparency && index < transparency.length ? transparency[index] : 255;
        } else if (colorType === 4) {
            const grey = pixels[source];
            data[target] = grey;
            data[target + 1] = grey;
            data[target + 2] = grey;
            data[target + 3] = pixels[source + 1];
        } else {
            data[target] = pixels[source];
            data[target + 1] = pixels[source + 1];
            data[target + 2] = pixels[source + 2];
            data[target + 3] = pixels[source + 3];
        }
    }

    return { width, height, data };
}

function writePng(file, image) {
    const { width, height, data } = image;
    const raw = Buffer.alloc((width * 4 + 1) * height);
    for (let y = 0; y < height; y += 1) {
        const rowStart = y * (width * 4 + 1);
        raw[rowStart] = 0;
        data.copy(raw, rowStart + 1, y * width * 4, (y + 1) * width * 4);
    }

    const header = Buffer.alloc(13);
    header.writeUInt32BE(width, 0);
    header.writeUInt32BE(height, 4);
    header[8] = 8;
    header[9] = 6;
    header[10] = 0;
    header[11] = 0;
    header[12] = 0;

    const png = Buffer.concat([
        SIGNATURE,
        chunk('IHDR', header),
        chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
        chunk('IEND', Buffer.alloc(0)),
    ]);
    if (typeof file === 'string') fs.writeFileSync(file, png);
    return png;
}

module.exports = { readPng, writePng };
