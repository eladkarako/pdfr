# PDFr instrumented tests (device)

These tests exercise the real MuPDF native library on an emulator or device.
They generate their own PDF/TXT/Markdown/DOCX/image fixtures at runtime, so
nothing binary is committed.

```bat
tools\create-test-avd.bat
tools\start-emulator.bat
tools\install-debug.bat
tools\run-instrumented-tests.bat
```

Run one class or one test:

```bat
tools\run-instrumented-tests.bat com.karako.pdfr.MuPdfDocumentInstrumentedTest
adb shell am instrument -w -e class com.karako.pdfr.MuPdfDocumentInstrumentedTest#reorderKeepsRequestedOrderAndDuplicates com.karako.pdfr.test/androidx.test.runner.AndroidJUnitRunner
```

## Files

| File | Purpose |
| --- | --- |
| `TestPdfWriter.kt` | A tiny PDF writer (no dependencies) using the built-in Helvetica font. Every page gets a visible, extractable label such as `Fixture page 3`. |
| `MuPdfDocumentInstrumentedTest.kt` | Opens, renders, selects text, reorders/rotates/compacts/linearizes, and reopens rewritten PDFs. Also covers password prompts, AES-256 fixtures, the plain-text monospace toggle, Markdown (including long-suffix aliases), DOCX, single-page images, and fixed-layout/reflowable zoom rendering. |

## What is covered

- PDF info (`kind`, `format`, page count, encryption flag).
- Page bounds and bitmap rendering.
- Text selection and copy input for the most important feature.
- `Reorder`: page order and duplicates are preserved (`[2, 0, 0, 3]` becomes
  pages 3, 1, 1, 4 of the source).
- `Rotate 180°`: only the listed pages get `/Rotate 180`, the others stay at 0.
- `Compact`: every page survives.
- `Linearize`: qpdf writes a file whose header contains `/Linearized` and which
  still opens and renders with MuPDF.
- Out-of-range page indices are rejected without leaving a partial file.
- Encrypted PDFs: no/wrong/correct password behaviour.
- Rewriting an encrypted PDF produces an unencrypted copy that opens with no
  password.
- Plain text: reflow, rendering, selection, both monospace states, and the
  `.txt`/`.nfo`/`.text`/`.log` extensions.
- Markdown: reflow and text extraction for `.md`, `.markdown`, `.mdown`, `.mkd`
  and `.mkdn` (the longer suffixes go through a temporary `.md` copy).
- DOCX: a generated OOXML package opens as reflowable HTML.
- Source/config text: `.cfg`, `.ini`, `.yaml`, `.yml`, `.json`, `.csv`, `.tsv`,
  `.c`, `.cpp`, `.js`, `.sh` and `.xml` open as plain text, and an unknown
  `.xyz` suffix falls back to text detection.
- PNG: opens as a one-page fixed-layout document and renders.
- Fixed-layout zoom: rendering at 800 px produces a bitmap proportionally
  larger than at 400 px.
- Reflow zoom: halving the layout width at the same em reflows long text onto
  more pages without losing the first or last paragraph.
- PDFs reject the text-only monospace override.

The gesture itself (live resize under the fingers, anchored centroid, deferred
reflow, two-finger pan) cannot be driven through `am instrument`, because it
needs real multitouch. `tools\zoom-probe.ps1` records that check as a sequence
of screenshots; see `tools\README.md`.

`MuPdfDocumentInstrumentedTest` dispatches every MuPDF call through
`MuPdf.dispatcher` and every qpdf call through `Qpdf.dispatcher`, matching the
contract in the app.
