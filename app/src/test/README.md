# PDFr test sources (JVM)

These tests run on the development machine, not on a device:

```bat
gradlew :app:testDebugUnitTest
:: or
tools\run-unit-tests.bat
```

They deliberately avoid Android classes, so they need no emulator and no
native MuPDF library.

Where a test covers a default, it reads the same central object the app uses
(`TextSettings`, `ZoomMath`, `NormalizeOptions`, `DocumentFormats`,
`MuPdfConfig`, `ReaderConfig`, `StorageConfig`), so a configuration change does
not require updating a duplicate value in a test. On-disk names (saved-state
keys, sidecar keys, XMP prefixes) are asserted literally because renaming one
silently loses data.

| File                   | What it covers                                                                                                                                                                                                                                                                   |
|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PageRangesTest`       | The `1-3,7,9-e,3` selection grammar: ranges, `e`, whitespace, order, duplicates, the `ALL_PAGES` default, input limits, and every error reason.                                                                                                                                  |
| `ZoomMathTest`         | The live zoom arithmetic: continuous values, 100%-600% clamping, pinch distance, double-tap stops, the reflow/bitmap scale hand-off, and the horizontal/vertical anchor maths that keeps the touched point under the fingers.                                                    |
| `DocumentFormatsTest`  | Extension/MIME mapping (PDF, ebooks, Markdown, images, source/config text), plain-text recognition, temporary-suffix aliases, text MIME detection, byte-level text sniffing, picker MIME list, Office formats staying unmapped, `extensionOf` rejecting opaque provider ids, the extension-length boundary and sampled text detection. |
| `DocumentInfoTest`     | PDF detection for the Reorder/Rotate/Normalize menu items and metadata presence.                                                                                                                                                                                         |
| `DocumentDatesTest`    | ISO `yyyy-MM-ddThh:mm:ss` parsing/formatting, PDF date offsets, missing components, invalid input and ISO-to-PDF conversion.                                                                                                                                                     |
| `PlainTextTest`        | UTF-8/16/32 BOM decoding, CRLF normalisation, line splitting, line-number digit counts, and copy-text building (only the phantom trailing newline is dropped).                                                                                                                   |
| `TextSettingsTest`     | Plain-text size limits: 5-40 pt range, 12 pt default, clamping out-of-range values and repairing an invalid stored size.                                                                                                                                                         |
| `TextPaginationTest`   | Line-to-page grouping for paged plain text: exact fits, tall and zero-height lines, the empty/unmeasured fallbacks, and every line covered exactly once.                                                                                                                         |
| `DocumentMetadataTest` | Filename/extension splitting, draft validation and editor limits, PDF metadata keys and date conversion, sidecar overlays (including cleared values), and sidecar round-trips.                                                                                                     |
| `MetadataTagsTest`     | PDF Info/XMP keys, standard-field slot mapping, XMP prefix/namespace uniqueness and the on-disk PDF dictionary spelling.                                                                                                                                                        |
| `MuPdfConfigTest`      | Cache budget ordering, layout/render limits, reader stylesheets and the shared PDF rewrite options alias.                                                                                                                                                                        |
| `ReaderConfigTest`     | Reader text defaults, viewport/point constants, timing and gesture thresholds, and the unique saved-state keys that survive rotation.                                                                                                                                           |
| `StorageConfigTest`    | Cache name limits, sidecar/share naming, epoch-seconds vs milliseconds detection, and `safeFileName` sanitising/UTF-8 truncation.                                                                                                                                                |
| `ImageMetadataFormatsTest` | PNG text/physical/time chunks, IPTC datasets, ICC description parsing and XMP read/update round-trips, including clearing values.                                                                                                                                            |
| `NormalizeOptionsTest` | Defaults, the all-off guard, JPEG-quality validation and apply enablement for the Normalize dialog.                                                                                                                                                                              |

Reports are written to
`build/app/reports/tests/testDebugUnitTest/index.html`.

Device-level coverage of MuPDF itself lives in
`app/src/androidTest/README.md`.
