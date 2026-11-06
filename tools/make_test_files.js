#!/usr/bin/env node
'use strict';

/**
 * Generate small documents for manual PDFr testing.
 *
 * Uses only Node.js built-in modules. Password-protected PDFs are produced with
 * qpdf when it is on PATH (https://qpdf.sourceforge.io); without it the other
 * fixtures are still written.
 *
 * The generated set mirrors app/src/androidTest: deterministic page labels make
 * it easy to see whether reordering kept the requested order and duplicates.
 *
 * Public-domain Project Gutenberg books are downloaded as well (unless
 * --no-gutenberg is given) to exercise EPUB, HTML and plain-text rendering.
 *
 *     node tools/make_test_files.js --out build/test-fixtures
 *
 * Requires Node.js 20.15+ or 22.2+ (for zlib.crc32).
 */

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const { execFileSync } = require('node:child_process');
const { parseArgs } = require('node:util');

const PASSWORD = 'secret';

// Small public-domain books used to exercise EPUB, HTML and plain-text
// rendering. The direct cache URLs are stable and avoid extra redirects.
const GUTENBERG_BOOKS = [
  {
    ebookId: 11,
    title: "Alice's Adventures in Wonderland",
    author: 'Lewis Carroll',
    url: 'https://www.gutenberg.org/cache/epub/11/pg11.epub',
    filename: 'alice-gutenberg.epub',
    kind: 'EPUB',
  },
  {
    ebookId: 1342,
    title: 'Pride and Prejudice',
    author: 'Jane Austen',
    url: 'https://www.gutenberg.org/cache/epub/1342/pg1342-images.epub',
    filename: 'pride-and-prejudice.epub',
    kind: 'EPUB (with images)',
  },
  {
    ebookId: 1661,
    title: 'The Adventures of Sherlock Holmes',
    author: 'Arthur Conan Doyle',
    url: 'https://www.gutenberg.org/cache/epub/1661/pg1661.txt',
    filename: 'sherlock-holmes.txt',
    kind: 'plain text',
  },
  {
    ebookId: 1952,
    title: 'The Yellow Wallpaper',
    author: 'Charlotte Perkins Gilman',
    url: 'https://www.gutenberg.org/cache/epub/1952/pg1952-images.html',
    filename: 'yellow-wallpaper.html',
    kind: 'HTML',
  },
];

// ---------------------------------------------------------------------------
// PDF
// ---------------------------------------------------------------------------

function escapePdfText(text) {
  return text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
}

function pageStream(label, number, count) {
  const text = `BT
/F1 30 Tf
72 700 Td
(${escapePdfText(label)}) Tj
ET
BT
/F1 11 Tf
72 668 Td
(PDFr generated fixture - page ${number} of ${count}) Tj
ET
0.85 w
48 48 516 696 re
S
`;
  return Buffer.from(text, 'latin1');
}

/** Write a simple, text-extractable multi-page PDF. */
function writePdf(file, labels) {
  if (labels.length === 0) {
    throw new Error('a fixture needs at least one page');
  }

  const chunks = [];
  const offsets = new Map();
  let length = 0;

  const emit = (data) => {
    const buf = Buffer.isBuffer(data) ? data : Buffer.from(data, 'latin1');
    chunks.push(buf);
    length += buf.length;
  };

  const obj = (objectId, body) => {
    offsets.set(objectId, length);
    emit(`${objectId} 0 obj\n${body}\nendobj\n`);
  };

  emit('%PDF-1.4\n');
  emit('%\u00e2\u00e3\u00cf\u00d3\n');

  const firstPage = 4;
  const kids = labels.map((_, index) => `${firstPage + index * 2} 0 R`).join(' ');

  obj(1, '<< /Type /Catalog /Pages 2 0 R >>');
  obj(2, `<< /Type /Pages /Count ${labels.length} /Kids [${kids}] >>`);
  obj(3, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>');

  labels.forEach((label, index) => {
    const pageId = firstPage + index * 2;
    const contentId = pageId + 1;
    const stream = pageStream(label, index + 1, labels.length);

    obj(
      pageId,
      '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ' +
        `/Resources << /Font << /F1 3 0 R >> >> /Contents ${contentId} 0 R >>`,
    );

    offsets.set(contentId, length);
    emit(`${contentId} 0 obj\n<< /Length ${stream.length} >>\nstream\n`);
    emit(stream);
    emit('\nendstream\nendobj\n');
  });

  const size = 4 + labels.length * 2;
  const xref = length;

  emit(`xref\n0 ${size}\n`);
  emit('0000000000 65535 f \n');
  for (let objectId = 1; objectId < size; objectId++) {
    emit(`${String(offsets.get(objectId)).padStart(10, '0')} 00000 n \n`);
  }
  emit(`trailer\n<< /Size ${size} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);

  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.concat(chunks));
}

/**
 * Encrypt with qpdf (AES-256) when installed. Returns false when unavailable.
 * `qpdf --check` verifies the result.
 */
function encryptPdf(source, destination, password) {
  try {
    execFileSync(
      'qpdf',
      ['--encrypt', password, password, '256', '--', source, destination],
      { stdio: 'ignore' },
    );
  } catch (err) {
    if (err.code === 'ENOENT') {
      return false;
    }
    throw new Error(`qpdf failed to encrypt ${source}: ${err.message}`);
  }

  // Fail fast if the result cannot be opened with the password.
  try {
    execFileSync('qpdf', [`--password=${password}`, '--check', destination], {
      stdio: 'ignore',
    });
  } catch (err) {
    throw new Error(`${destination} rejected its own password`);
  }
  return true;
}

// ---------------------------------------------------------------------------
// ZIP-based fixtures
// ---------------------------------------------------------------------------

/**
 * Write a ZIP archive using the STORED method (no compression), matching
 * Python's zipfile.ZIP_STORED. A fixed DOS timestamp (1980-01-01 00:00) keeps
 * the output deterministic.
 */
function writeZipStored(file, entries) {
  const DOS_TIME = 0;
  const DOS_DATE = (0 << 9) | (1 << 5) | 1;

  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const crc = zlib.crc32(data) >>> 0;

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); // local file header signature
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(0, 6); // flags
    local.writeUInt16LE(0, 8); // method: stored
    local.writeUInt16LE(DOS_TIME, 10);
    local.writeUInt16LE(DOS_DATE, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18); // compressed size
    local.writeUInt32LE(data.length, 22); // uncompressed size
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28); // extra length
    localParts.push(local, nameBuf, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); // central directory signature
    central.writeUInt16LE(20, 4); // version made by
    central.writeUInt16LE(20, 6); // version needed
    central.writeUInt16LE(0, 8); // flags
    central.writeUInt16LE(0, 10); // method: stored
    central.writeUInt16LE(DOS_TIME, 12);
    central.writeUInt16LE(DOS_DATE, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt16LE(0, 30); // extra length
    central.writeUInt16LE(0, 32); // comment length
    central.writeUInt16LE(0, 34); // disk number start
    central.writeUInt16LE(0, 36); // internal attributes
    central.writeUInt32LE(0, 38); // external attributes
    central.writeUInt32LE(offset, 42); // local header offset
    centralParts.push(central, nameBuf);

    offset += local.length + nameBuf.length + data.length;
  }

  const centralDir = Buffer.concat(centralParts);

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); // end of central directory signature
  eocd.writeUInt16LE(0, 4); // disk number
  eocd.writeUInt16LE(0, 6); // disk with central directory
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralDir.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20); // comment length

  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.concat([...localParts, centralDir, eocd]));
}

function writeEpub(file, title, chapters) {
  const chapterFiles = [];
  const manifests = [];
  const spine = [];
  const navItems = [];

  chapters.forEach(([chapterTitle, body], index) => {
    const order = index + 1;
    const filename = `chapter${order}.xhtml`;
    chapterFiles.push({
      name: `EPUB/${filename}`,
      content: `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
<head><title>${chapterTitle}</title></head>
<body>
<h1>${chapterTitle}</h1>
${body}
</body>
</html>
`,
    });
    manifests.push(
      `<item id="chapter${order}" href="${filename}" media-type="application/xhtml+xml"/>`,
    );
    spine.push(`<itemref idref="chapter${order}"/>`);
    navItems.push(`<li><a href="${filename}">${chapterTitle}</a></li>`);
  });

  // Deterministic identifier. Python's hash() is randomized per process.
  const idHash = (zlib.crc32(Buffer.from(title, 'utf8')) >>> 0).toString(16);

  const contentOpf = `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:pdfr-fixture-${idHash}</dc:identifier>
    <dc:title>${title}</dc:title>
    <dc:language>en</dc:language>
    <dc:creator>PDFr</dc:creator>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    ${manifests.join('')}
  </manifest>
  <spine>
    ${spine.join('')}
  </spine>
</package>
`;

  const navXhtml = `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Contents</title></head>
<body>
<h1>Contents</h1>
<ol>${navItems.join('')}</ol>
</body>
</html>
`;

  const containerXml = `<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="EPUB/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
`;

  writeZipStored(file, [
    { name: 'mimetype', data: Buffer.from('application/epub+zip', 'utf8') },
    { name: 'META-INF/container.xml', data: Buffer.from(containerXml, 'utf8') },
    { name: 'EPUB/content.opf', data: Buffer.from(contentOpf, 'utf8') },
    { name: 'EPUB/nav.xhtml', data: Buffer.from(navXhtml, 'utf8') },
    ...chapterFiles.map(({ name, content }) => ({
      name,
      data: Buffer.from(content, 'utf8'),
    })),
  ]);
}

// ---------------------------------------------------------------------------
// PNG / CBZ
// ---------------------------------------------------------------------------

function pngChunk(tag, data) {
  const tagBuf = Buffer.from(tag, 'latin1');
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(zlib.crc32(Buffer.concat([tagBuf, data])) >>> 0, 0);
  return Buffer.concat([length, tagBuf, data, crc]);
}

function png(width, height, [r, g, b]) {
  const row = Buffer.alloc(1 + width * 3); // filter byte 0, then RGB pixels
  for (let x = 0; x < width; x++) {
    row[1 + x * 3] = r;
    row[2 + x * 3] = g;
    row[3 + x * 3] = b;
  }
  const raw = Buffer.concat(Array(height).fill(row));

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8; // bit depth
  header[9] = 2; // colour type: RGB
  header[10] = 0; // compression
  header[11] = 0; // filter
  header[12] = 0; // interlace

  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  return Buffer.concat([
    signature,
    pngChunk('IHDR', header),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

function writeCbz(file, pages) {
  const entries = pages.map((color, index) => ({
    name: `page${String(index + 1).padStart(2, '0')}.png`,
    data: png(800, 1200, color),
  }));
  writeZipStored(file, entries);
}

// ---------------------------------------------------------------------------
// Project Gutenberg
// ---------------------------------------------------------------------------

async function downloadGutenberg(destination, timeoutMs) {
  fs.mkdirSync(destination, { recursive: true });
  const downloaded = [];

  for (const book of GUTENBERG_BOOKS) {
    const target = path.join(destination, book.filename);
    const partial = `${target}.part`;

    try {
      const response = await fetch(book.url, {
        headers: { 'User-Agent': 'PDFr-test-fixtures/1.0' },
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = Buffer.from(await response.arrayBuffer());
      if (data.length < 1024) {
        throw new Error('download was unexpectedly small');
      }
      if (book.filename.endsWith('.epub') && data.subarray(0, 2).toString('latin1') !== 'PK') {
        throw new Error('not a ZIP/EPUB file');
      }

      fs.writeFileSync(partial, data);
      fs.renameSync(partial, target);
      downloaded.push(book);
      console.log(`[PDFr] Gutenberg ${book.filename.padEnd(30)} ${String(data.length).padStart(9)} bytes`);
    } catch (err) {
      fs.rmSync(partial, { force: true });
      console.warn(`[PDFr] Skipped Gutenberg ${book.filename}: ${err.message}`);
    }
  }

  if (downloaded.length > 0) {
    const lines = [
      'Project Gutenberg test fixtures',
      '===============================',
      '',
      'These files are public-domain works downloaded from Project Gutenberg.',
      'They are used only to exercise PDFr EPUB, HTML and plain-text reading.',
      '',
    ];
    for (const book of downloaded) {
      lines.push(
        book.filename,
        `  Title:  ${book.title}`,
        `  Author: ${book.author}`,
        `  Format: ${book.kind}`,
        `  Source: ${book.url}`,
        `  eBook:  https://www.gutenberg.org/ebooks/${book.ebookId}`,
        '',
      );
    }
    lines.push(
      'Project Gutenberg: https://www.gutenberg.org/',
      'Permission is granted to copy, distribute and modify these works',
      'under the Project Gutenberg License included with each eBook.',
      '',
    );
    fs.writeFileSync(path.join(destination, 'SOURCES.txt'), lines.join('\n'), 'utf8');
  }

  return downloaded.length;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  const repo = path.resolve(__dirname, '..');

  const { values } = parseArgs({
    options: {
      out: { type: 'string', default: path.join(repo, 'build', 'test-fixtures') },
      pages: { type: 'string', default: '14' },
      password: { type: 'string', default: PASSWORD },
      'no-encrypted': { type: 'boolean', default: false },
      'no-gutenberg': { type: 'boolean', default: false },
      'gutenberg-timeout': { type: 'string', default: '30000' },
      help: { type: 'boolean', short: 'h', default: false },
    },
  });

  if (values.help) {
    console.log(
      'Usage: node make_test_files.js [--out DIR] [--pages N] [--password PW]\n' +
        '       [--no-encrypted] [--no-gutenberg] [--gutenberg-timeout MS]',
    );
    return 0;
  }

  const pageCount = Number.parseInt(values.pages, 10);
  if (!Number.isInteger(pageCount) || pageCount < 1) {
    console.error('[PDFr] --pages must be a positive integer');
    return 2;
  }

  const out = path.resolve(values.out);
  fs.mkdirSync(out, { recursive: true });

  const sample = path.join(out, 'sample.pdf');
  writePdf(
    sample,
    Array.from({ length: pageCount }, (_, i) => `Sample page ${i + 1}`),
  );

  const dummy = path.join(out, 'dummy.pdf');
  writePdf(dummy, ['Dummy PDF file']);

  const encrypted = path.join(out, 'dummy-encrypted.pdf');
  let encryptedOk = false;
  if (!values['no-encrypted']) {
    encryptedOk = encryptPdf(dummy, encrypted, values.password);
  }

  const notes = path.join(out, 'notes.txt');
  const notesBody = `PDFr plain text fixture
=======================

The quick brown fox jumps over the lazy dog.
Pack my box with five dozen liquor jugs.

0123456789 - ABCDEFGHIJKLMNOPQRSTUVWXYZ
abcdefghijklmnopqrstuvwxyz

    indented line
    another indented line

A second paragraph with enough words to wrap across a line and show
how MuPDF turns plain text into reflowable HTML pages.

<This should stay literal angle brackets>
`;
  fs.writeFileSync(notes, notesBody, 'utf8');

  // .nfo and .text are common in scene/readme archives. They must take the
  // same plain-text path as .txt.
  fs.writeFileSync(
    path.join(out, 'README.nfo'),
    `PDFr NFO fixture
================
  _   _   _   _
 | |_| |_| |_| |
 |  _  | . |  _|
 |_| |_|___|_|

Extension: .nfo
The quick brown fox jumps over the lazy dog.
`,
    'utf8',
  );
  fs.writeFileSync(
    path.join(out, 'book.text'),
    `PDFr .text fixture

A long paragraph for the reflowable reader. ${'The reader wraps this line to the current screen width. '.repeat(8)}

A second paragraph proves that blank lines still start a new block.
`,
    'utf8',
  );

  const words = (list, times) => Array(times).fill(list).flat().join(' ');

  writeEpub(path.join(out, 'alice.epub'), 'PDFr fixture book', [
    [
      'Chapter 1: Down the rabbit hole',
      `<p>${words(
        ['Alice', 'was', 'beginning', 'to', 'get', 'very', 'tired', 'of', 'sitting'],
        40,
      )}</p>`,
    ],
    [
      'Chapter 2: The pool of tears',
      `<p>${words(
        ['Curiouser', 'and', 'curiouser', 'cried', 'Alice', 'as', 'she', 'grew'],
        40,
      )}</p>`,
    ],
  ]);

  writeCbz(path.join(out, 'sample.cbz'), [
    [220, 60, 60],
    [60, 140, 220],
    [70, 170, 90],
  ]);

  if (!values['no-gutenberg']) {
    const timeoutMs = Number.parseInt(values['gutenberg-timeout'], 10);
    const timeout = Number.isInteger(timeoutMs) && timeoutMs > 0 ? timeoutMs : 30000;
    await downloadGutenberg(path.join(out, 'gutenberg'), timeout);
  }

  console.log(`[PDFr] Fixtures written to ${out}`);
  for (const name of fs.readdirSync(out).sort()) {
    const { size } = fs.statSync(path.join(out, name));
    console.log(`  ${name.padEnd(24)} ${String(size).padStart(8)} bytes`);
  }

  if (!values['no-encrypted'] && !encryptedOk) {
    console.error(
      '[PDFr] qpdf is not installed, so dummy-encrypted.pdf was skipped.\n' +
        '       Install it from https://qpdf.sourceforge.io or with your package manager.',
    );
  } else if (encryptedOk) {
    console.log(`[PDFr] dummy-encrypted.pdf password: ${values.password}`);
  }
  return 0;
}

if (require.main === module) {
  main()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((err) => {
      console.error(`[PDFr] ${err.message}`);
      process.exitCode = 1;
    });
}
