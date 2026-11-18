# PDFr instrumented tests (device)

These tests exercise the real MuPDF native library on an emulator or device.
They generate their own PDF/TXT/Markdown/image fixtures at runtime, so
nothing binary is committed.

```bat
tools\create-test-avd.bat
tools\start-emulator.bat
tools\install-debug.bat
tools\run-instrumented-tests.bat
```

The TV checks can run on the phone AVD as well; to use a real leanback
environment, create and start the TV AVD instead:

```bat
tools\create-tv-avd.bat
tools\start-emulator.bat pdfr_tv
tools\run-instrumented-tests.bat com.karako.pdfr.TvSupportInstrumentedTest
```

Run one class or one test:

```bat
tools\run-instrumented-tests.bat com.karako.pdfr.MuPdfDocumentInstrumentedTest
adb shell am instrument -w -e class com.karako.pdfr.MuPdfDocumentInstrumentedTest#reorderKeepsRequestedOrderAndDuplicates com.karako.pdfr.test/androidx.test.runner.AndroidJUnitRunner
```

## Files

| File                               | Purpose                                                                                                                                                                                                                                                                                                     |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `TestPdfWriter.kt`                 | A tiny PDF writer (no dependencies) using the built-in Helvetica font. Every page gets a visible, extractable label such as `Fixture page 3`.                                                                                                                                                               |
| `MuPdfDocumentInstrumentedTest.kt` | Opens, renders, selects text, reorders/rotates/normalizes/fixes, edits PDF metadata, and reopens rewritten PDFs. Also covers password prompts, AES-256 fixtures, the plain-text monospace toggle, Markdown (including long-suffix aliases), single-page images, and fixed-layout/reflowable zoom rendering. |
| `ImageMetadataInstrumentedTest.kt` | Reads and writes JPEG/PNG/WebP metadata through the real Android `ExifInterface`, checks XMP/IPTC/ICC fallbacks and sidecar behaviour, and verifies that unsupported formats stay read-only.                                                                                                                |
| `DocumentStoreInstrumentedTest.kt` | Imports a document through `ContentResolver`, checks the cache layout, safe names and timestamps, stale-document cleanup, empty-file rejection, derived rewrite files, share copies and discard (including the metadata sidecar).                                                                           |
| `AppSettingsInstrumentedTest.kt`   | Stores and repairs the theme and language preferences, and checks that the locale-wrapped context selects the real Hebrew resources with an RTL layout direction (and English with LTR).                                                                                                                    |
| `AccessibilityInstrumentedTest.kt` | Reads the real accessibility tree: every toolbar button has a label and a 48dp target, the fixed left-to-right order holds in Hebrew, only the document actions are disabled with no file, and Tab reaches the overflow menu and opens it with the D-pad select key.                                        |
| `TvSupportInstrumentedTest.kt`     | Checks the leanback launcher entry, the 320x180 banner, `supportsRtl`, and that the phone launcher entry still exists, so one APK serves phones and TV.                                                                                                                                                     |
| `PrintInstrumentedTest.kt`         | Drives `ReaderViewModel.printSource()` for a PDF, plain text, an image and an encrypted PDF: unencrypted PDFs are handed over unchanged, the rest become a temporary PDF that is deleted again after the spooler reads it.                                                                                  |

## What is covered

- PDF info (`kind`, `format`, page count, encryption flag).
- Page bounds and bitmap rendering.
- Text selection and copy input for the most important feature. `selectAll`
  walks native text blocks, so a page title and footer in separate blocks both
  contribute highlight quads and copy text.
- `Reorder`: page order and duplicates are preserved (`[2, 0, 0, 3]` becomes
  pages 3, 1, 1, 4 of the source).
- `Rotate 180°`: only the listed pages get `/Rotate 180`, the others stay at 0.
- `Normalize`: qpdf writes a file whose header contains `/Linearized` when
  linearization is selected, adds `/GTS_PDFA1` and `pdfaid:part` when PDF/A is
  selected, and the result still opens and renders with MuPDF.
- `Fix PDF`: qpdf's QDF repair cycle writes a normal PDF with no `%QDF` markers
  that still opens and renders every page with MuPDF.
- PDF metadata: a rewrite sets title/author/creation date, and an empty value
  removes the title while leaving the other properties intact.
- Out-of-range page indices are rejected without leaving a partial file.
- Encrypted PDFs: no/wrong/correct password behaviour.
- Rewriting an encrypted PDF produces an unencrypted copy that opens with no
  password.
- Plain text: reflow, rendering, selection, both monospace states, and the
  `.txt`/`.nfo`/`.text`/`.log` extensions.
- Markdown: reflow and text extraction for `.md`, `.markdown`, `.mdown`, `.mkd`
  and `.mkdn` (the longer suffixes go through a temporary `.md` copy).
- DOCX/XLSX/PPTX/HWPX: intentionally unsupported, so the reader must not claim
  them in the picker or the Open with/Share filters.
- Source/config text: `.cfg`, `.ini`, `.yaml`, `.yml`, `.json`, `.csv`, `.tsv`,
  `.c`, `.cpp`, `.js`, `.sh` and `.xml` open as plain text, and an unknown
  `.xyz` suffix falls back to text detection.
- PNG: opens as a one-page fixed-layout document and renders.
- Fixed-layout zoom: rendering at 800 px produces a bitmap proportionally
  larger than at 400 px.
- Reflow zoom: halving the layout width at the same em reflows long text onto
  more pages without losing the first or last paragraph.
- PDFs reject the text-only monospace override.
- `DocumentStore`: importing copies into `cache/documents/<uuid>/<name>`, keeps
  the source timestamp, sanitises illegal characters, rejects empty files,
  removes the previous document, allocates unique rewrite directories, clears
  old share copies and deletes the sidecar with a discarded document.
- Accessibility: the toolbar's content descriptions, 48dp minimum touch targets,
  the fixed Share → Print → Info → Open → Save → Menu order, the enabled state
  of each button in the empty state, Tab reaching the overflow menu, and
  KEYCODE_DPAD_CENTER opening it.
- Hebrew UI: the toolbar keeps its left-to-right order while its labels come
  from `values-iw`, and the overflow menu shows the translated entry.
- TV: `LEANBACK_LAUNCHER` resolves to `MainActivity`, the phone launcher entry
  remains, the banner is exactly 320x180, and `android:supportsRtl` stays on.
- Printing: `printSource()` returns the cached PDF unchanged, converts plain
  text and images to a temporary PDF, decrypts an encrypted PDF without asking
  for the password again, and `discard()` deletes every temporary copy.

The gesture itself (live resize under the fingers, anchored centroid, deferred
reflow, two-finger pan) cannot be driven through `am instrument`, because it
needs real multitouch. `tools\zoom-probe.ps1` records that check as a sequence
of screenshots, and `tools\reader-actions.ps1` covers long-press, Select all,
Copy and the menu/dialog checks; see `tools\README.md`.

`MuPdfDocumentInstrumentedTest` dispatches every MuPDF call through
`MuPdf.dispatcher` and every qpdf call through `Qpdf.dispatcher`, matching the
contract in the app.
