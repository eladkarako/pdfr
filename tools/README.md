# Test-fixture generator

Generates the documents used for manual testing. It writes them to
`build/test-fixtures/` (`../build/test-fixtures/` from the script's point of
view).

Everything except the encrypted PDF is produced with Node's built-in modules.
The encrypted PDF needs `qpdf` on `PATH` or next to the script.

### Windows

```bat
tools\make_test_files.cmd
```

`make_test_files.cmd` downloads Node and qpdf into `tools/` when they are
missing from the folder or `PATH`. It uses `curl` and `7z`, which are assumed to
be available.

### Other systems

You need `node` (20.15+ or 22.2+, for `zlib.crc32`) and `qpdf` either next to
the script or on `PATH`.

```bash
node tools/make_test_files.js --out build/test-fixtures
python3 tools/fetch_gutenberg.py --out build/test-fixtures/gutenberg
```

The JavaScript generator also downloads the Gutenberg books itself unless you
pass `--no-gutenberg`. `fetch_gutenberg.py` is the standalone equivalent and
uses only the Python standard library.

## Output

| File | Contents |
| --- | --- |
| `sample.pdf` | 14 letter-size pages labelled `Sample page 1` … `Sample page 14`. |
| `dummy.pdf` | A one-page PDF. |
| `dummy-encrypted.pdf` | A one-page PDF encrypted with AES-256 (password `secret`). |
| `notes.txt` | Plain text with long lines, indentation and literal angle brackets. |
| `README.nfo` | A small ASCII-art readme that exercises the `.nfo` plain-text path. |
| `book.text` | Long reflowable paragraphs for the `.text` plain-text path. |
| `alice.epub` | A minimal two-chapter EPUB 3 book. |
| `sample.cbz` | Three solid-colour PNG pages. |
| `gutenberg/` | Public-domain EPUB, TXT and HTML books from Project Gutenberg, plus `SOURCES.txt` listing each URL and eBook id. |

The generated PDFs are real, text-extractable documents (base-14 Helvetica), so
selection, reordering, rotation and qpdf linearization all work on them.

### Gutenberg fixtures

| File | Book | Format |
| --- | --- | --- |
| `alice-gutenberg.epub` | *Alice's Adventures in Wonderland* | EPUB |
| `pride-and-prejudice.epub` | *Pride and Prejudice* | EPUB with images |
| `sherlock-holmes.txt` | *The Adventures of Sherlock Holmes* | plain text |
| `yellow-wallpaper.html` | *The Yellow Wallpaper* | HTML |

They are public-domain works downloaded from `https://www.gutenberg.org/`. Each
file's source URL is recorded in `gutenberg/SOURCES.txt`. Downloads are skipped
(with a warning) when offline, so fixture generation still succeeds without a
network.

## Options

```text
--out DIR                output directory (default: <repo>/build/test-fixtures)
--pages N                number of pages in sample.pdf (default 14)
--password TEXT          password for dummy-encrypted.pdf (default secret)
--no-encrypted           skip dummy-encrypted.pdf
--no-gutenberg            skip the Project Gutenberg downloads
--gutenberg-timeout MS   per-download timeout in milliseconds (default 30000)
```

`fetch_gutenberg.py` additionally supports `--list` and `--only <slug>`
(`alice`, `pride`, `sherlock`, `yellow`).

## Used in testing

- `adb.exe` pushes the fixtures to the device.
- `scripts\push-fixtures.bat` runs the generator and copies the results to
  `/sdcard/Download/pdfr-fixtures/`.
- The instrumented tests generate their own tiny PDFs at runtime instead of
  reading these files.
