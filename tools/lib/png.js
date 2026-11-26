'use strict';

/**
 * Minimal PNG reader and writer built on Node's zlib.
 *
 * Android screenshots and the project's generated images are ordinary 8-bit
 * PNGs, so this covers the non-interlaced colour types they use: grey, RGB,
 * palette, grey+alpha and RGBA. Everything is expanded to RGBA, which keeps
 * the drawing helpers simple. No external packages are needed.
 *
 * The reader is deliberately strict about malformed files: a missing palette,
 * a palette index past the end, a bad CRC, truncated image data or an absurd
 * pixel count all raise instead of producing quietly wrong colours.
 *
 *     const { readPng, writePng } = require('./lib/png');
 *     const image = readPng('shot.png'); // { width, height, data }
 *     writePng('out.png', image);
 */

const fs = require('node:fs');
const zlib = require('node:zlib');

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
// 50 megapixels is 200 MB of RGBA output. Anything larger is almost certainly
// a decompression bomb rather than a screenshot or a generated image.
const MAX_PIXELS = 50_000_000;
const CHANNELS = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 };

const CRC_TABLE = (() => {
    const table = new Uint32Array(256);
    for (let n = 0; n < 256; n += 1) {
        let c = n;
        for (let k = 0; k < 8; k += 1) {
            c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        }
        table[n] = c >>> 0;
    }
    return table;
})();

function crc32(buffer) {
    let crc = 0xffffffff;
    for (const byte of buffer) {
        crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
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

/**
 * Reverses the PNG scanline filter in place. `row` holds filtered bytes on
 * entry and raw bytes on exit. `previous` is the already-unfiltered row above
 * (all zeroes for the first row). `channels` is the PNG bytes-per-pixel.
 */
function unfilterRow(filter, row, previous, channels) {
    const stride = row.length;
    switch (filter) {
        case 0:
            break;
        case 1:
            for (let x = channels; x < stride; x += 1) {
                row[x] = (row[x] + row[x - channels]) & 0xff;
            }
            break;
        case 2:
            for (let x = 0; x < stride; x += 1) {
                row[x] = (row[x] + previous[x]) & 0xff;
            }
            break;
        case 3:
            for (let x = 0; x < stride; x += 1) {
                const left = x >= channels ? row[x - channels] : 0;
                row[x] = (row[x] + ((left + previous[x]) >> 1)) & 0xff;
            }
            break;
        case 4:
            for (let x = 0; x < stride; x += 1) {
                const left = x >= channels ? row[x - channels] : 0;
                const upLeft = x >= channels ? previous[x - channels] : 0;
                row[x] = (row[x] + paeth(left, previous[x], upLeft)) & 0xff;
            }
            break;
        default:
            throw new Error(`Unknown PNG filter: ${filter}`);
    }
}

/**
 * Converts one unfiltered row into RGBA bytes written to `out`.
 * `key` is the tRNS colour key for types 0 and 2 (or null).
 * `alpha` is the tRNS alpha table for type 3 (or null).
 */
function expandRow(row, out, colorType, width, palette, key, alpha) {
    switch (colorType) {
        case 0:
            for (let x = 0; x < width; x += 1) {
                const grey = row[x];
                const d = x * 4;
                out[d] = grey;
                out[d + 1] = grey;
                out[d + 2] = grey;
                out[d + 3] = key && grey === key[0] ? 0 : 255;
            }
            break;
        case 2:
            for (let x = 0; x < width; x += 1) {
                const s = x * 3;
                const d = x * 4;
                const r = row[s];
                const g = row[s + 1];
                const b = row[s + 2];
                out[d] = r;
                out[d + 1] = g;
                out[d + 2] = b;
                out[d + 3] = key && r === key[0] && g === key[1] && b === key[2] ? 0 : 255;
            }
            break;
        case 3: {
            const entries = palette.length / 3;
            for (let x = 0; x < width; x += 1) {
                const index = row[x];
                if (index >= entries) {
                    throw new Error(`PNG palette index out of range: ${index}`);
                }
                const s = index * 3;
                const d = x * 4;
                out[d] = palette[s];
                out[d + 1] = palette[s + 1];
                out[d + 2] = palette[s + 2];
                out[d + 3] = alpha && index < alpha.length ? alpha[index] : 255;
            }
            break;
        }
        case 4:
            for (let x = 0; x < width; x += 1) {
                const s = x * 2;
                const d = x * 4;
                const grey = row[s];
                out[d] = grey;
                out[d + 1] = grey;
                out[d + 2] = grey;
                out[d + 3] = row[s + 1];
            }
            break;
        case 6:
            out.set(row.subarray(0, width * 4));
            break;
        default:
            throw new Error(`Unsupported PNG colour type: ${colorType}`);
    }
}

/**
 * Reads an 8-bit, non-interlaced PNG.
 *
 * @param {string|Buffer} file Path to a PNG or the PNG bytes themselves.
 * @returns {{width: number, height: number, data: Buffer}} RGBA pixels,
 *     `width * height * 4` bytes, row-major from the top-left.
 * @throws {Error} For a non-PNG file, a truncated or corrupt chunk, a bad CRC,
 *     unsupported bit depth/interlace/colour type, a missing palette, an
 *     out-of-range palette index, a truncated image stream, or a pixel count
 *     above the safety limit.
 */
function readPng(file) {
    const buffer = Buffer.isBuffer(file) ? file : fs.readFileSync(file);
    if (buffer.length < SIGNATURE.length || !buffer.subarray(0, 8).equals(SIGNATURE)) {
        throw new Error('Not a PNG file');
    }

    let offset = 8;
    let header = null;
    let palette = null;
    let transparency = null;
    let sawEnd = false;
    const idat = [];

    while (offset + 12 <= buffer.length) {
        const length = buffer.readUInt32BE(offset);
        const end = offset + 12 + length;
        if (end > buffer.length) throw new Error('Truncated PNG chunk');

        const type = buffer.toString('ascii', offset + 4, offset + 8);
        const data = buffer.subarray(offset + 8, offset + 8 + length);
        const expected = buffer.readUInt32BE(offset + 8 + length);
        if (crc32(buffer.subarray(offset + 4, offset + 8 + length)) !== expected) {
            throw new Error(`Corrupt PNG chunk: ${type}`);
        }
        offset = end;

        if (type === 'IHDR') {
            if (length !== 13) throw new Error('Invalid PNG IHDR chunk');
            header = {
                width: data.readUInt32BE(0),
                height: data.readUInt32BE(4),
                bitDepth: data[8],
                colorType: data[9],
                compression: data[10],
                filterMethod: data[11],
                interlace: data[12],
            };
        } else if (type === 'PLTE') {
            palette = data;
        } else if (type === 'tRNS') {
            transparency = data;
        } else if (type === 'IDAT') {
            idat.push(data);
        } else if (type === 'IEND') {
            sawEnd = true;
            break;
        }
    }

    if (!header) throw new Error('PNG has no IHDR');
    if (!sawEnd) throw new Error('PNG has no IEND');
    if (idat.length === 0) throw new Error('PNG has no IDAT');

    const { width, height, bitDepth, colorType, compression, filterMethod, interlace } = header;
    if (!width || !height) throw new Error('PNG has zero width or height');
    if (width * height > MAX_PIXELS) throw new Error('PNG is too large to load');
    if (bitDepth !== 8) throw new Error(`Unsupported PNG bit depth: ${bitDepth}`);
    if (interlace !== 0) throw new Error('Interlaced PNGs are not supported');
    if (compression !== 0 || filterMethod !== 0) {
        throw new Error('Unsupported PNG compression or filter method');
    }

    const channels = CHANNELS[colorType];
    if (!channels) throw new Error(`Unsupported PNG colour type: ${colorType}`);

    if (colorType === 3) {
        if (!palette || palette.length === 0 || palette.length % 3 !== 0) {
            throw new Error('Indexed PNG has no valid PLTE chunk');
        }
        if (palette.length > 768) throw new Error('PNG palette has too many entries');
    }

    // Colour keys for tRNS. Types 4 and 6 already carry alpha, so tRNS is
    // ignored there. For an 8-bit image the significant sample is the low byte
    // of each 16-bit tRNS value.
    let key = null;
    let alpha = null;
    if (transparency) {
        if (colorType === 0 && transparency.length >= 2) {
            key = [transparency[1]];
        } else if (colorType === 2 && transparency.length >= 6) {
            key = [transparency[1], transparency[3], transparency[5]];
        } else if (colorType === 3) {
            alpha = transparency;
        }
    }

    const stride = width * channels;
    const expectedRaw = (stride + 1) * height;
    const raw = zlib.inflateSync(Buffer.concat(idat), { maxOutputLength: expectedRaw });
    if (raw.length !== expectedRaw) throw new Error('PNG image data is truncated');

    const rowBytes = width * 4;
    const data = Buffer.alloc(rowBytes * height);
    let previous = Buffer.alloc(stride);

    for (let y = 0; y < height; y += 1) {
        const start = y * (stride + 1);
        const filter = raw[start];
        const row = raw.subarray(start + 1, start + 1 + stride);
        unfilterRow(filter, row, previous, channels);
        expandRow(
            row,
            data.subarray(y * rowBytes, (y + 1) * rowBytes),
            colorType,
            width,
            palette,
            key,
            alpha,
        );
        previous = row;
    }

    return { width, height, data };
}

/**
 * Encodes an RGBA image as PNG bytes. Uses the Sub filter on every row, which
 * usually compresses screenshots better than unfiltered data.
 *
 * @param {{width: number, height: number, data: Buffer}} image RGBA pixels.
 * @returns {Buffer} A complete PNG file.
 * @throws {RangeError} When the dimensions are not positive integers or the
 *     data length does not match `width * height * 4`.
 */
function encodePng(image) {
    const { width, height, data } = image;
    if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1) {
        throw new RangeError('Image dimensions must be positive integers');
    }

    const rowBytes = width * 4;
    if (!Buffer.isBuffer(data) || data.length !== rowBytes * height) {
        throw new RangeError('Image data does not match width and height');
    }

    const raw = Buffer.alloc((rowBytes + 1) * height);
    for (let y = 0; y < height; y += 1) {
        const src = y * rowBytes;
        const dest = y * (rowBytes + 1);
        raw[dest] = 1; // Sub filter: each byte is stored relative to the pixel to its left.
        for (let i = 0; i < rowBytes; i += 1) {
            const left = i >= 4 ? data[src + i - 4] : 0;
            raw[dest + 1 + i] = (data[src + i] - left) & 0xff;
        }
    }

    const header = Buffer.alloc(13);
    header.writeUInt32BE(width, 0);
    header.writeUInt32BE(height, 4);
    header.writeUInt8(8, 8); // bit depth
    header.writeUInt8(6, 9); // colour type: RGBA
    // compression, filter method and interlace stay 0

    return Buffer.concat([
        SIGNATURE,
        chunk('IHDR', header),
        chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
        chunk('IEND', Buffer.alloc(0)),
    ]);
}

/**
 * Encodes the image and writes it to `file`. Returns the PNG bytes as well, so
 * callers that only need a buffer can ignore the file argument.
 *
 * @param {string} file Destination path.
 * @param {{width: number, height: number, data: Buffer}} image RGBA pixels.
 * @returns {Buffer} The bytes written to `file`.
 */
function writePng(file, image) {
    const png = encodePng(image);
    fs.writeFileSync(file, png);
    return png;
}

module.exports = { readPng, writePng, encodePng };
