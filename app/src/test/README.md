# PDFr test sources (JVM)

These tests run on the development machine, not on a device:

```bat
gradlew :app:testDebugUnitTest
:: or
tools\run-unit-tests.bat
```

They deliberately avoid Android classes, so they need no emulator and no
native MuPDF library.

| File | What it covers |
| --- | --- |
| `PageRangesTest` | The `1-3,7,9-e,3` selection grammar: ranges, `e`, whitespace, order, duplicates, and every error reason. |
| `DocumentFormatsTest` | Extension/MIME mapping (PDF, ebooks, Markdown, OOXML Office, images, source/config text), plain-text recognition, temporary-suffix aliases, text MIME detection, byte-level text sniffing, picker MIME list, and `extensionOf` rejecting opaque provider ids. |
| `DocumentInfoTest` | PDF detection for the Reorder/Rotate/Linearize/Compact menu items and metadata presence. |

Reports are written to
`build/app/reports/tests/testDebugUnitTest/index.html`.

Device-level coverage of MuPDF itself lives in
`app/src/androidTest/README.md`.
