'use strict';

/**
 * Small RGBA raster helpers used by the image tools.
 *
 * Everything works on the `{ width, height, data }` shape returned by
 * `lib/png`. Shapes and text are drawn with alpha blending, so the helpers can
 * be layered without a separate compositing library. Text uses a compact
 * built-in 5x7 bitmap font: lowercase letters share the uppercase glyphs, and
 * punctuation outside the set is drawn as a missing-glyph box.
 */

const FONT = {
    A: ['01110', '10001', '10001', '11111', '10001', '10001', '10001'],
    B: ['11110', '10001', '10001', '11110', '10001', '10001', '11110'],
    C: ['01110', '10001', '10000', '10000', '10000', '10001', '01110'],
    D: ['11110', '10001', '10001', '10001', '10001', '10001', '11110'],
    E: ['11111', '10000', '10000', '11110', '10000', '10000', '11111'],
    F: ['11111', '10000', '10000', '11110', '10000', '10000', '10000'],
    G: ['01110', '10001', '10000', '10111', '10001', '10001', '01111'],
    H: ['10001', '10001', '10001', '11111', '10001', '10001', '10001'],
    I: ['11111', '00100', '00100', '00100', '00100', '00100', '11111'],
    J: ['00111', '00010', '00010', '00010', '00010', '10010', '01100'],
    K: ['10001', '10010', '10100', '11000', '10100', '10010', '10001'],
    L: ['10000', '10000', '10000', '10000', '10000', '10000', '11111'],
    M: ['10001', '11011', '10101', '10101', '10001', '10001', '10001'],
    N: ['10001', '11001', '10101', '10011', '10001', '10001', '10001'],
    O: ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
    P: ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
    Q: ['01110', '10001', '10001', '10001', '10101', '10010', '01101'],
    R: ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
    S: ['01111', '10000', '10000', '01110', '00001', '00001', '11110'],
    T: ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
    U: ['10001', '10001', '10001', '10001', '10001', '10001', '01110'],
    V: ['10001', '10001', '10001', '10001', '10001', '01010', '00100'],
    W: ['10001', '10001', '10001', '10101', '10101', '10101', '01010'],
    X: ['10001', '10001', '01010', '00100', '01010', '10001', '10001'],
    Y: ['10001', '10001', '01010', '00100', '00100', '00100', '00100'],
    Z: ['11111', '00001', '00010', '00100', '01000', '10000', '11111'],
    0: ['01110', '10011', '10101', '10101', '10101', '11001', '01110'],
    1: ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
    2: ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
    3: ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
    4: ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
    5: ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
    6: ['00110', '01000', '10000', '11110', '10001', '10001', '01110'],
    7: ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
    8: ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
    9: ['01110', '10001', '10001', '01111', '00001', '00010', '01100'],
    '-': ['00000', '00000', '00000', '11111', '00000', '00000', '00000'],
    '.': ['00000', '00000', '00000', '00000', '00000', '00110', '00110'],
    ',': ['00000', '00000', '00000', '00000', '00110', '00110', '01100'],
    ':': ['00000', '00110', '00110', '00000', '00110', '00110', '00000'],
    ';': ['00000', '00110', '00110', '00000', '00110', '00110', '01100'],
    '!': ['00100', '00100', '00100', '00100', '00100', '00000', '00100'],
    '?': ['01110', '10001', '00001', '00010', '00100', '00000', '00100'],
    '/': ['00001', '00010', '00010', '00100', '01000', '01000', '10000'],
    '\\': ['10000', '01000', '01000', '00100', '00010', '00010', '00001'],
    '|': ['00100', '00100', '00100', '00100', '00100', '00100', '00100'],
    '+': ['00000', '00100', '00100', '11111', '00100', '00100', '00000'],
    '=': ['00000', '00000', '11111', '00000', '11111', '00000', '00000'],
    '(': ['00010', '00100', '01000', '01000', '01000', '00100', '00010'],
    ')': ['01000', '00100', '00010', '00010', '00010', '00100', '01000'],
    "'": ['00100', '00100', '00000', '00000', '00000', '00000', '00000'],
    '"': ['01010', '01010', '00000', '00000', '00000', '00000', '00000'],
    '#': ['01010', '01010', '11111', '01010', '11111', '01010', '01010'],
    '%': ['11001', '11010', '00100', '00100', '01000', '01011', '10011'],
    '&': ['01100', '10010', '10100', '01000', '10101', '10010', '01101'],
    '@': ['01110', '10001', '10111', '10101', '10111', '10000', '01110'],
    '*': ['00000', '10101', '01110', '11111', '01110', '10101', '00000'],
    '<': ['00010', '00100', '01000', '10000', '01000', '00100', '00010'],
    '>': ['01000', '00100', '00010', '00001', '00010', '00100', '01000'],
    '_': ['00000', '00000', '00000', '00000', '00000', '00000', '11111'],
    ' ': ['00000', '00000', '00000', '00000', '00000', '00000', '00000'],
};

function parseColor(value) {
    if (Array.isArray(value)) {
        return [value[0] | 0, value[1] | 0, value[2] | 0, value[3] === undefined ? 255 : value[3] | 0];
    }
    if (typeof value === 'number') {
        return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff, 255];
    }
    let text = String(value).trim().replace(/^#/, '');
    if (text.length === 3) text = text.split('').map((part) => part + part).join('');
    if (text.length === 6) text += 'ff';
    if (!/^[0-9a-fA-F]{8}$/.test(text)) throw new Error(`Bad colour: ${value}`);
    return [
        Number.parseInt(text.slice(0, 2), 16),
        Number.parseInt(text.slice(2, 4), 16),
        Number.parseInt(text.slice(4, 6), 16),
        Number.parseInt(text.slice(6, 8), 16),
    ];
}

function createImage(width, height, color = '#00000000') {
    const rgba = parseColor(color);
    const data = Buffer.alloc(width * height * 4);
    for (let index = 0; index < width * height; index += 1) {
        data[index * 4] = rgba[0];
        data[index * 4 + 1] = rgba[1];
        data[index * 4 + 2] = rgba[2];
        data[index * 4 + 3] = rgba[3];
    }
    return { width, height, data };
}

function blendPixel(image, x, y, color, coverage = 1) {
    x = Math.round(x);
    y = Math.round(y);
    if (x < 0 || y < 0 || x >= image.width || y >= image.height) return;
    const rgba = Array.isArray(color) ? color : parseColor(color);
    const alpha = (rgba[3] / 255) * Math.max(0, Math.min(1, coverage));
    if (alpha <= 0) return;
    const offset = (y * image.width + x) * 4;
    const data = image.data;
    const inverse = 1 - alpha;
    data[offset] = Math.round(rgba[0] * alpha + data[offset] * inverse);
    data[offset + 1] = Math.round(rgba[1] * alpha + data[offset + 1] * inverse);
    data[offset + 2] = Math.round(rgba[2] * alpha + data[offset + 2] * inverse);
    data[offset + 3] = Math.round(rgba[3] * alpha + data[offset + 3] * inverse);
}

function mixColor(start, end, amount) {
    const a = parseColor(start);
    const b = parseColor(end);
    const t = Math.max(0, Math.min(1, amount));
    return [
        Math.round(a[0] + (b[0] - a[0]) * t),
        Math.round(a[1] + (b[1] - a[1]) * t),
        Math.round(a[2] + (b[2] - a[2]) * t),
        Math.round(a[3] + (b[3] - a[3]) * t),
    ];
}

function fillRect(image, x, y, width, height, color) {
    const rgba = parseColor(color);
    const left = Math.max(0, Math.floor(x));
    const top = Math.max(0, Math.floor(y));
    const right = Math.min(image.width, Math.ceil(x + width));
    const bottom = Math.min(image.height, Math.ceil(y + height));
    for (let py = top; py < bottom; py += 1) {
        for (let px = left; px < right; px += 1) blendPixel(image, px, py, rgba);
    }
}

function fillLinearGradient(image, x0, y0, x1, y1, start, end) {
    const dx = x1 - x0;
    const dy = y1 - y0;
    const lengthSquared = dx * dx + dy * dy || 1;
    for (let y = 0; y < image.height; y += 1) {
        for (let x = 0; x < image.width; x += 1) {
            const t = ((x - x0) * dx + (y - y0) * dy) / lengthSquared;
            blendPixel(image, x, y, mixColor(start, end, t));
        }
    }
}

function insideRoundRect(px, py, x, y, width, height, radius) {
    const r = Math.max(0, Math.min(radius, width / 2, height / 2));
    if (px < x || py < y || px > x + width || py > y + height) return false;
    if (r === 0) return true;
    const cx = Math.min(Math.max(px, x + r), x + width - r);
    const cy = Math.min(Math.max(py, y + r), y + height - r);
    const dx = px - cx;
    const dy = py - cy;
    return dx * dx + dy * dy <= r * r;
}

function fillRoundRect(image, x, y, width, height, radius, color) {
    const rgba = parseColor(color);
    const left = Math.max(0, Math.floor(x));
    const top = Math.max(0, Math.floor(y));
    const right = Math.min(image.width, Math.ceil(x + width));
    const bottom = Math.min(image.height, Math.ceil(y + height));
    for (let py = top; py < bottom; py += 1) {
        for (let px = left; px < right; px += 1) {
            if (insideRoundRect(px + 0.5, py + 0.5, x, y, width, height, radius)) {
                blendPixel(image, px, py, rgba);
            }
        }
    }
}

function strokeRoundRect(image, x, y, width, height, radius, thickness, color) {
    const rgba = parseColor(color);
    const inset = thickness;
    const left = Math.max(0, Math.floor(x));
    const top = Math.max(0, Math.floor(y));
    const right = Math.min(image.width, Math.ceil(x + width));
    const bottom = Math.min(image.height, Math.ceil(y + height));
    for (let py = top; py < bottom; py += 1) {
        for (let px = left; px < right; px += 1) {
            const cx = px + 0.5;
            const cy = py + 0.5;
            if (!insideRoundRect(cx, cy, x, y, width, height, radius)) continue;
            const inner = insideRoundRect(
                cx, cy,
                x + inset, y + inset,
                width - inset * 2, height - inset * 2,
                Math.max(0, radius - inset),
            );
            if (!inner) blendPixel(image, px, py, rgba);
        }
    }
}

function fillPolygon(image, points, color) {
    const rgba = parseColor(color);
    const xs = points.map((point) => point[0]);
    const ys = points.map((point) => point[1]);
    const left = Math.max(0, Math.floor(Math.min(...xs)));
    const right = Math.min(image.width, Math.ceil(Math.max(...xs)));
    const top = Math.max(0, Math.floor(Math.min(...ys)));
    const bottom = Math.min(image.height, Math.ceil(Math.max(...ys)));
    for (let py = top; py < bottom; py += 1) {
        for (let px = left; px < right; px += 1) {
            let inside = false;
            const x = px + 0.5;
            const y = py + 0.5;
            for (let i = 0, j = points.length - 1; i < points.length; j = i, i += 1) {
                const xi = points[i][0];
                const yi = points[i][1];
                const xj = points[j][0];
                const yj = points[j][1];
                if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
                    inside = !inside;
                }
            }
            if (inside) blendPixel(image, px, py, rgba);
        }
    }
}

function drawLine(image, x0, y0, x1, y1, thickness, color) {
    const dx = x1 - x0;
    const dy = y1 - y0;
    const lengthSquared = dx * dx + dy * dy || 1;
    const radius = thickness / 2;
    const left = Math.max(0, Math.floor(Math.min(x0, x1) - radius));
    const right = Math.min(image.width, Math.ceil(Math.max(x0, x1) + radius));
    const top = Math.max(0, Math.floor(Math.min(y0, y1) - radius));
    const bottom = Math.min(image.height, Math.ceil(Math.max(y0, y1) + radius));
    for (let py = top; py < bottom; py += 1) {
        for (let px = left; px < right; px += 1) {
            const cx = px + 0.5;
            const cy = py + 0.5;
            const t = Math.max(0, Math.min(1, ((cx - x0) * dx + (cy - y0) * dy) / lengthSquared));
            const nearX = x0 + dx * t;
            const nearY = y0 + dy * t;
            const distance = Math.hypot(cx - nearX, cy - nearY);
            if (distance <= radius + 0.5) {
                blendPixel(image, px, py, color, Math.min(1, radius + 0.5 - distance));
            }
        }
    }
}

function sample(source, x, y) {
    const clampedX = Math.max(0, Math.min(source.width - 1, x));
    const clampedY = Math.max(0, Math.min(source.height - 1, y));
    const offset = (clampedY * source.width + clampedX) * 4;
    return [
        source.data[offset],
        source.data[offset + 1],
        source.data[offset + 2],
        source.data[offset + 3],
    ];
}

/** Bilinear scale of `source` into the destination rectangle. */
function drawImage(image, source, x, y, width, height) {
    const left = Math.max(0, Math.floor(x));
    const top = Math.max(0, Math.floor(y));
    const right = Math.min(image.width, Math.ceil(x + width));
    const bottom = Math.min(image.height, Math.ceil(y + height));
    for (let py = top; py < bottom; py += 1) {
        for (let px = left; px < right; px += 1) {
            const u = ((px + 0.5 - x) / width) * source.width - 0.5;
            const v = ((py + 0.5 - y) / height) * source.height - 0.5;
            const x0 = Math.floor(u);
            const y0 = Math.floor(v);
            const fx = u - x0;
            const fy = v - y0;
            const a = sample(source, x0, y0);
            const b = sample(source, x0 + 1, y0);
            const c = sample(source, x0, y0 + 1);
            const d = sample(source, x0 + 1, y0 + 1);
            const rgba = [0, 1, 2, 3].map((channel) => {
                const topValue = a[channel] + (b[channel] - a[channel]) * fx;
                const bottomValue = c[channel] + (d[channel] - c[channel]) * fx;
                return Math.round(topValue + (bottomValue - topValue) * fy);
            });
            blendPixel(image, px, py, rgba);
        }
    }
}

/** Bilinear scale clipped to a rounded rectangle. */
function drawImageInRoundRect(image, source, x, y, width, height, radius) {
    const left = Math.max(0, Math.floor(x));
    const top = Math.max(0, Math.floor(y));
    const right = Math.min(image.width, Math.ceil(x + width));
    const bottom = Math.min(image.height, Math.ceil(y + height));
    for (let py = top; py < bottom; py += 1) {
        for (let px = left; px < right; px += 1) {
            if (!insideRoundRect(px + 0.5, py + 0.5, x, y, width, height, radius)) continue;
            const u = ((px + 0.5 - x) / width) * source.width - 0.5;
            const v = ((py + 0.5 - y) / height) * source.height - 0.5;
            const x0 = Math.floor(u);
            const y0 = Math.floor(v);
            const fx = u - x0;
            const fy = v - y0;
            const a = sample(source, x0, y0);
            const b = sample(source, x0 + 1, y0);
            const c = sample(source, x0, y0 + 1);
            const d = sample(source, x0 + 1, y0 + 1);
            const rgba = [0, 1, 2, 3].map((channel) => {
                const topValue = a[channel] + (b[channel] - a[channel]) * fx;
                const bottomValue = c[channel] + (d[channel] - c[channel]) * fx;
                return Math.round(topValue + (bottomValue - topValue) * fy);
            });
            blendPixel(image, px, py, rgba);
        }
    }
}

function glyphFor(character) {
    const upper = character.toUpperCase();
    return FONT[character] || FONT[upper] || FONT['?'];
}

function measureText(text, scale, spacing = scale) {
    return String(text).length * (5 * scale + spacing) - spacing;
}

/**
 * Draws text with the built-in 5x7 font. `y` is the top of the caps, and
 * `scale` multiplies each font pixel, so scale 6 gives a 42 px tall title.
 */
function drawText(image, text, x, y, scale, color, options = {}) {
    const spacing = options.spacing === undefined ? scale : options.spacing;
    const bold = options.bold === true;
    let cursor = x;
    for (const character of String(text)) {
        if (character === '\n') continue;
        const glyph = glyphFor(character);
        for (let row = 0; row < 7; row += 1) {
            for (let column = 0; column < 5; column += 1) {
                if (glyph[row][column] !== '1') continue;
                const px = cursor + column * scale;
                const py = y + row * scale;
                fillRect(image, px, py, scale, scale, color);
                if (bold) fillRect(image, px + scale, py, 1, scale, color);
            }
        }
        cursor += 5 * scale + spacing;
    }
    return cursor;
}

module.exports = {
    blendPixel,
    createImage,
    drawImage,
    drawImageInRoundRect,
    drawLine,
    drawText,
    fillLinearGradient,
    fillPolygon,
    fillRect,
    fillRoundRect,
    measureText,
    mixColor,
    parseColor,
    strokeRoundRect,
};
