'use strict';

/**
 * Self-test for lib/png.js.
 *
 * Runs without a test framework or any npm package: `node tools/png-selftest.js`
 * or `tools\png-selftest.bat`. The fixtures are built here with an independent
 * chunk writer and an independent filter encoder, so a bug shared by the
 * reader and the writer cannot make a round trip pass by accident.
 */

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const { readPng, writePng, encodePng } = require('./lib/png');

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

function filterRow(raw, previous, bpp, type) {
    const out = Buffer.alloc(raw.length);
    for (let x = 0; x < raw.length; x += 1) {
        const left = x >= bpp ? raw[x - bpp] : 0;
        const up = previous ? previous[x] : 0;
        const upLeft = previous && x >= bpp ? previous[x - bpp] : 0;
        let predictor = 0;
        if (type === 1) predictor = left;
        else if (type === 2) predictor = up;
        else if (type === 3) predictor = (left + up) >> 1;
        else if (type === 4) predictor = paeth(left, up, upLeft);
        else if (type !== 0) throw new Error(`bad test filter ${type}`);
        out[x] = (raw[x] - predictor) & 0xff;
    }
    return out;
}

function buildPng(options) {
    const {
        width,
        height,
        colorType,
        rows,
        filters = [],
        palette = null,
        transparency = null,
        bitDepth = 8,
        interlace = 0,
        idatOverride = null,
        includeIend = true,
    } = options;

    const header = Buffer.alloc(13);
    header.writeUInt32BE(width, 0);
    header.writeUInt32BE(height, 4);
    header.writeUInt8(bitDepth, 8);
    header.writeUInt8(colorType, 9);
    header.writeUInt8(0, 10);
    header.writeUInt8(0, 11);
    header.writeUInt8(interlace, 12);

    const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
    let idatData = idatOverride;
    if (idatData === null) {
        const stride = width * channels;
        const raw = Buffer.alloc((stride + 1) * height);
        let previous = null;
        for (let y = 0; y < height; y += 1) {
            const filter = filters[y] ?? 0;
            raw[y * (stride + 1)] = filter;
            filterRow(rows[y], previous, channels, filter).copy(raw, y * (stride + 1) + 1);
            previous = rows[y];
        }
        idatData = zlib.deflateSync(raw);
    }

    const chunks = [chunk('IHDR', header)];
    if (palette) chunks.push(chunk('PLTE', palette));
    if (transparency) chunks.push(chunk('tRNS', transparency));
    chunks.push(chunk('IDAT', idatData));
    if (includeIend) chunks.push(chunk('IEND', Buffer.alloc(0)));
    return Buffer.concat([SIGNATURE, ...chunks]);
}

function rgbaImage(width, height) {
    const data = Buffer.alloc(width * height * 4);
    for (let i = 0; i < data.length; i += 1) data[i] = (i * 37 + 11) & 0xff;
    return { width, height, data };
}

const tests = [];
function test(name, fn) {
    tests.push({ name, fn });
}

test('short input is rejected as not a PNG', () => {
    assert.throws(() => readPng(Buffer.alloc(4)), /Not a PNG file/);
    assert.throws(() => readPng(Buffer.from('not a png at all')), /Not a PNG file/);
});

test('RGBA round trip through encodePng/readPng', () => {
    const image = rgbaImage(37, 23);
    const back = readPng(encodePng(image));
    assert.equal(back.width, 37);
    assert.equal(back.height, 23);
    assert.deepEqual(back.data, image.data);
});

test('writePng writes the same bytes it returns', () => {
    const image = rgbaImage(9, 5);
    const file = path.join(os.tmpdir(), `pdfr-png-${process.pid}.png`);
    try {
        const bytes = writePng(file, image);
        assert.deepEqual(fs.readFileSync(file), bytes);
        assert.deepEqual(readPng(file).data, image.data);
    } finally {
        fs.rmSync(file, { force: true });
    }
});

test('writer emits the Sub filter with the correct predictor', () => {
    const image = rgbaImage(3, 1);
    const png = encodePng(image);
    const idatAt = png.indexOf(Buffer.from('IDAT', 'ascii'));
    const length = png.readUInt32BE(idatAt - 4);
    const raw = zlib.inflateSync(png.subarray(idatAt + 4, idatAt + 4 + length));
    assert.equal(raw.length, 1 + image.data.length);
    assert.equal(raw[0], 1);
    for (let i = 0; i < image.data.length; i += 1) {
        const left = i >= 4 ? image.data[i - 4] : 0;
        assert.equal(raw[1 + i], (image.data[i] - left) & 0xff, `filtered byte ${i}`);
    }
});

test('all five scanline filters are reversible', () => {
    const width = 5;
    const height = 5;
    const data = rgbaImage(width, height).data;
    const rows = [];
    for (let y = 0; y < height; y += 1) {
        rows.push(data.subarray(y * width * 4, (y + 1) * width * 4));
    }
    const png = buildPng({
        width,
        height,
        colorType: 6,
        rows,
        filters: [0, 1, 2, 3, 4],
    });
    assert.deepEqual(readPng(png).data, data);
});

test('greyscale tRNS colour key becomes transparent', () => {
    const png = buildPng({
        width: 3,
        height: 1,
        colorType: 0,
        rows: [Buffer.from([0, 128, 255])],
        transparency: Buffer.from([0, 128]),
    });
    const image = readPng(png);
    assert.deepEqual([...image.data], [
        0, 0, 0, 255,
        128, 128, 128, 0,
        255, 255, 255, 255,
    ]);
});

test('RGB tRNS colour key becomes transparent', () => {
    const png = buildPng({
        width: 2,
        height: 1,
        colorType: 2,
        rows: [Buffer.from([10, 20, 30, 40, 50, 60])],
        transparency: Buffer.from([0, 10, 0, 20, 0, 30]),
    });
    const image = readPng(png);
    assert.deepEqual([...image.data], [
        10, 20, 30, 0,
        40, 50, 60, 255,
    ]);
});

test('palette and tRNS alpha are expanded to RGBA', () => {
    const png = buildPng({
        width: 2,
        height: 1,
        colorType: 3,
        rows: [Buffer.from([0, 1])],
        palette: Buffer.from([255, 0, 0, 0, 0, 255]),
        transparency: Buffer.from([128]),
    });
    assert.deepEqual([...readPng(png).data], [
        255, 0, 0, 128,
        0, 0, 255, 255,
    ]);
});

test('indexed PNG without a palette is rejected', () => {
    const png = buildPng({
        width: 1,
        height: 1,
        colorType: 3,
        rows: [Buffer.from([0])],
    });
    assert.throws(() => readPng(png), /PLTE/);
});

test('out-of-range palette index is rejected', () => {
    const png = buildPng({
        width: 1,
        height: 1,
        colorType: 3,
        rows: [Buffer.from([1])],
        palette: Buffer.from([1, 2, 3]),
    });
    assert.throws(() => readPng(png), /palette index out of range/);
});

test('a flipped byte fails the CRC check', () => {
    const png = encodePng(rgbaImage(4, 4));
    const idatAt = png.indexOf(Buffer.from('IDAT', 'ascii'));
    png[idatAt + 5] ^= 0x01;
    assert.throws(() => readPng(png), /Corrupt PNG chunk: IDAT/);
});

test('missing IEND is rejected', () => {
    const png = buildPng({
        width: 1,
        height: 1,
        colorType: 6,
        rows: [Buffer.from([1, 2, 3, 4])],
        includeIend: false,
    });
    assert.throws(() => readPng(png), /no IEND/);
});

test('short image stream is rejected', () => {
    const png = buildPng({
        width: 2,
        height: 2,
        colorType: 6,
        rows: [Buffer.from([1, 2, 3, 4, 5, 6, 7, 8])],
        idatOverride: zlib.deflateSync(Buffer.alloc(4)),
    });
    assert.throws(() => readPng(png), /image data is truncated/);
});

test('oversized images are rejected before inflating', () => {
    const png = buildPng({
        width: 10000,
        height: 10000,
        colorType: 6,
        rows: [Buffer.from([0, 0, 0, 0])],
        idatOverride: zlib.deflateSync(Buffer.alloc(1)),
    });
    assert.throws(() => readPng(png), /too large to load/);
});

test('a decompression bomb stops at the expected size', () => {
    const png = buildPng({
        width: 1,
        height: 1,
        colorType: 6,
        rows: [Buffer.from([0, 0, 0, 0])],
        idatOverride: zlib.deflateSync(Buffer.alloc(4096)),
    });
    assert.throws(() => readPng(png));
});

test('interlaced and non-8-bit images are rejected', () => {
    const interlaced = buildPng({
        width: 1,
        height: 1,
        colorType: 6,
        rows: [Buffer.from([0, 0, 0, 0])],
        interlace: 1,
    });
    assert.throws(() => readPng(interlaced), /Interlaced/);

    const packed = buildPng({
        width: 1,
        height: 1,
        colorType: 6,
        rows: [Buffer.from([0, 0, 0, 0])],
        bitDepth: 4,
    });
    assert.throws(() => readPng(packed), /bit depth/);
});

test('encodePng validates its image input', () => {
    assert.throws(
        () => encodePng({ width: 2, height: 2, data: Buffer.alloc(3) }),
        /does not match/,
    );
    assert.throws(
        () => encodePng({ width: 0, height: 2, data: Buffer.alloc(0) }),
        /positive integers/,
    );
    assert.throws(
        () => encodePng({ width: 2, height: 2, data: [1, 2, 3, 4] }),
        /does not match/,
    );
});

let failures = 0;
for (const { name, fn } of tests) {
    try {
        fn();
        console.log(`[OK] ${name}`);
    } catch (error) {
        failures += 1;
        console.error(`[FAIL] ${name}`);
        console.error(`       ${error.message}`);
    }
}

if (failures > 0) {
    console.error(`\n${failures} of ${tests.length} PNG checks failed.`);
    process.exit(1);
}
console.log(`\n[OK] ${tests.length} PNG checks passed.`);
