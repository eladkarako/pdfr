#!/usr/bin/env node
'use strict';

/**
 * Downloads small public-domain books from Project Gutenberg.
 *
 * These fixtures exercise the non-PDF formats MuPDF understands (EPUB, HTML
 * and plain text). Gutenberg books are in the public domain in the United
 * States; the download page and the book itself carry the exact notice. This
 * script stores the source URL next to every file so a reviewer can check it.
 *
 * Uses only Node.js built-ins (plus tools/lib/common.js).
 *
 *     node tools\fetch-gutenberg.js --out build/test-fixtures/gutenberg
 *     node tools\fetch-gutenberg.js --list
 *     node tools\fetch-gutenberg.js --only alice --only sherlock
 *     tools\fetch-gutenberg.bat --out build/test-fixtures/gutenberg
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');

const {
    REPO_ROOT,
    formatBytes,
    outDir,
    parseCli,
    runMain,
    say,
    warn,
} = require('./lib/common');

const BOOKS = [
    {
        slug: 'alice',
        ebookId: 11,
        title: "Alice's Adventures in Wonderland",
        author: 'Lewis Carroll',
        url: 'https://www.gutenberg.org/cache/epub/11/pg11.epub',
        filename: 'alice-gutenberg.epub',
        kind: 'EPUB',
    },
    {
        slug: 'pride',
        ebookId: 1342,
        title: 'Pride and Prejudice',
        author: 'Jane Austen',
        url: 'https://www.gutenberg.org/cache/epub/1342/pg1342-images.epub',
        filename: 'pride-and-prejudice.epub',
        kind: 'EPUB (with images)',
    },
    {
        slug: 'sherlock',
        ebookId: 1661,
        title: 'The Adventures of Sherlock Holmes',
        author: 'Arthur Conan Doyle',
        url: 'https://www.gutenberg.org/cache/epub/1661/pg1661.txt',
        filename: 'sherlock-holmes.txt',
        kind: 'plain text',
    },
    {
        slug: 'yellow',
        ebookId: 1952,
        title: 'The Yellow Wallpaper',
        author: 'Charlotte Perkins Gilman',
        url: 'https://www.gutenberg.org/cache/epub/1952/pg1952-images.html',
        filename: 'yellow-wallpaper.html',
        kind: 'HTML',
    },
];

const USER_AGENT = 'PDFr-test-fixtures/1.0 (https://github.com/eladkarako/pdfr)';

function printManifest() {
    say('Project Gutenberg fixtures:');
    for (const book of BOOKS) {
        say(`  ${book.filename.padEnd(32)} ${book.kind.padEnd(18)} #${book.ebookId} ${book.title} - ${book.author}`);
        say(`  ${' '.repeat(32)} ${book.url}`);
    }
}

async function download(book, destination, timeoutSeconds) {
    const target = path.join(destination, book.filename);
    const partial = `${target}.part`;
    fs.rmSync(partial, { force: true });

    try {
        const response = await fetch(book.url, {
            headers: { 'User-Agent': USER_AGENT, Accept: '*/*' },
            signal: AbortSignal.timeout(timeoutSeconds * 1000),
        });
        if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
        if (!response.body) throw new Error('empty response');

        await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(partial));

        if (fs.statSync(partial).size < 1024) {
            throw new Error('download was unexpectedly small');
        }

        // A Gutenberg EPUB is a ZIP; make sure the server returned the book
        // rather than an HTML error page.
        if (book.filename.endsWith('.epub')) {
            const magic = fs.readFileSync(partial).subarray(0, 2).toString('latin1');
            if (magic !== 'PK') throw new Error('not a ZIP/EPUB file');
        }

        fs.renameSync(partial, target);
        say(`[PDFr] ${book.filename.padEnd(32)} ${formatBytes(fs.statSync(target).size).padStart(9)} bytes`);
        return true;
    } catch (error) {
        fs.rmSync(partial, { force: true });
        warn(`Skipped ${book.filename}: ${error.message}`);
        return false;
    }
}

function writeSources(destination, downloaded) {
    const lines = [
        'Project Gutenberg test fixtures',
        '===============================',
        '',
        'These files are public-domain works downloaded from Project Gutenberg.',
        "They are used only to exercise PDFr's EPUB, HTML and plain-text reader.",
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
    fs.writeFileSync(path.join(destination, 'SOURCES.txt'), lines.join(os.EOL), 'utf8');
}

/** `--only` is repeatable, but parseCli replaces list values, so merge them. */
function mergeRepeatedOnly(argv) {
    const rest = [];
    const slugs = [];
    let sawOnly = false;

    for (let index = 0; index < argv.length; index += 1) {
        const token = argv[index];
        const equals = token.indexOf('=');
        const rawName = equals === -1 ? token : token.slice(0, equals);
        const isOnly = token.startsWith('-')
            && rawName.replace(/^-+/, '').replace(/[-_]/g, '').toLowerCase() === 'only';
        if (!isOnly) {
            rest.push(token);
            continue;
        }

        let value = equals === -1 ? undefined : token.slice(equals + 1);
        if (value === undefined) {
            if (index + 1 >= argv.length) throw new Error(`Option ${rawName} needs a value.`);
            value = argv[index + 1];
            index += 1;
        }
        sawOnly = true;
        slugs.push(value);
    }

    if (sawOnly) rest.push('--only', slugs.join(','));
    return rest;
}

runMain(async () => {
    const args = parseCli(mergeRepeatedOnly(process.argv.slice(2)), {
        Out: { type: 'string', default: outDir(path.join('test-fixtures', 'gutenberg')) },
        Only: { type: 'list', default: [] },
        Timeout: { type: 'double', default: 60.0 },
        List: { type: 'bool', default: false },
    });

    if (args.List) {
        printManifest();
        return 0;
    }

    let selected = BOOKS;
    if (args.Only.length) {
        const wanted = new Set(args.Only.map((slug) => slug.toLowerCase()));
        selected = BOOKS.filter((book) => wanted.has(book.slug));
        if (selected.length === 0) {
            warn('No matching book. Use --list to see the available slugs.');
            return 2;
        }
    }

    const out = path.resolve(args.Out);
    fs.mkdirSync(out, { recursive: true });

    const downloaded = [];
    for (const book of selected) {
        if (await download(book, out, args.Timeout)) downloaded.push(book);
    }

    if (downloaded.length) {
        writeSources(out, downloaded);
        say(`[PDFr] Gutenberg fixtures written to ${out}`);
        return 0;
    }

    warn('No Gutenberg fixtures were downloaded (offline or blocked).');
    return 1;
});
