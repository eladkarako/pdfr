# PDFr test sources (JVM)

These tests run on the development machine, not on a device:

```bat
gradlew :app:testDebugUnitTest
:: or
tools\run-unit-tests.bat
```

They deliberately avoid Android classes, so they need no emulator and no
native MuPDF library.

| File                   | What it covers                                                                                                                                                                                                                                                                   |
|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PageRangesTest`       | The `1-3,7,9-e,3` selection grammar: ranges, `e`, whitespace, order, duplicates, and every error reason.                                                                                                                                                                         |
| `ZoomMathTest`         | The live zoom arithmetic: continuous values, 100%-600% clamping, pinch distance, double-tap stops, the reflow/bitmap scale hand-off, and the horizontal/vertical anchor maths that keeps the touched point under the fingers.                                                    |
| `DocumentFormatsTest`  | Extension/MIME mapping (PDF, ebooks, Markdown, images, source/config text), plain-text recognition, temporary-suffix aliases, text MIME detection, byte-level text sniffing, picker MIME list, Office formats staying unmapped, and `extensionOf` rejecting opaque provider ids. |
| `DocumentInfoTest`     | PDF detection for the Reorder/Rotate/Linearize/Compact menu items and metadata presence.                                                                                                                                                                                         |
| `DocumentDatesTest`    | ISO `yyyy-MM-ddThh:mm:ss` parsing/formatting, PDF date offsets, missing components, invalid input and ISO-to-PDF conversion.                                                                                                                                                     |
| `PlainTextTest`        | UTF-8/16/32 BOM decoding, CRLF normalisation, line splitting and line-number digit counts.                                                                                                                                                                                       |
| `TextSettingsTest`     | Plain-text size limits: 5-40 pt range, 12 pt default, clamping out-of-range values and repairing an invalid stored size.                                                                                                                                                         |
| `TextPaginationTest`   | Line-to-page grouping for paged plain text: exact fits, tall and zero-height lines, the empty/unmeasured fallbacks, and every line covered exactly once.                                                                                                                         |
| `DocumentMetadataTest` | Filename/extension splitting, draft validation, PDF metadata keys and date conversion, sidecar overlays (including cleared values), and sidecar round-trips.                                                                                                                     |

Reports are written to
`build/app/reports/tests/testDebugUnitTest/index.html`.

Device-level coverage of MuPDF itself lives in
`app/src/androidTest/README.md`.
