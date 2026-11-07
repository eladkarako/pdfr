# PDFr tools

Windows helpers for building, installing and testing PDFr, plus the generators
for the manual-test documents. Run the batch files from anywhere; they locate
the repository relative to themselves.

## Helper scripts

Before the first run, make sure `ANDROID_SDK_ROOT` is set (or the SDK is in
the default `%LOCALAPPDATA%\Android\Sdk`). Everything can be overridden:

| Variable | Default | Meaning |
| --- | --- | --- |
| `ANDROID_SDK_ROOT` / `ANDROID_HOME` | auto-detected | Android SDK location |
| `ADB` | `%ANDROID_SDK_ROOT%\platform-tools\adb.exe` | adb binary |
| `EMULATOR` | `%ANDROID_SDK_ROOT%\emulator\emulator.exe` | emulator binary |
| `PDFR_AVD` | `qpdf_full` | AVD to start (override if your AVD has another name) |
| `PDFR_FIXTURES` | `<repo>\build\test-fixtures` | generated fixture folder |

### The short version

```bat
tools\create-test-avd.bat
tools\start-emulator.bat
tools\install-debug.bat
tools\push-fixtures.bat
tools\manual-testing.bat
```

### Scripts

| Script | What it does |
| --- | --- |
| `create-test-avd.bat [name] [ram-mb]` | Creates the `qpdf_full` API 35 x86_64 AVD with 1536 MB RAM. Installs the system image if it is missing. Safe to re-run. |
| `start-emulator.bat [avd]` | Starts the AVD without a snapshot and waits until `sys.boot_completed`. |
| `stop-emulator.bat` | `adb emu kill`. |
| `install-debug.bat [abi]` | Builds `:app:assembleDebug`, detects the device ABI and installs the matching split APK. |
| `run-unit-tests.bat` | Runs the JVM tests (`:app:testDebugUnitTest`) and prints the report path. |
| `run-instrumented-tests.bat [class]` | Builds app + test APKs, installs both, and runs `AndroidJUnitRunner` on the device. Optional class filter. |
| `push-fixtures.bat` | Generates the fixtures and downloads a few public-domain Project Gutenberg books (EPUB/TXT/HTML) into `gutenberg/`. Then pushes everything to `/sdcard/Download/pdfr-fixtures/`. |
| `open-fixture.bat <name> [mime]` | Restarts PDFr, stages a generated fixture inside its cache with `run-as`, then sends the app its own FileProvider URI. Needs the debug build. Falls back to a MediaStore `ACTION_VIEW` if the app is not debuggable. |
| `manual-testing.bat` | Interactive menu for the scenarios below. |
| `gestures.ps1 <gesture>` | Raw multitouch/tap helpers: `pinch-out`, `pinch-in`, `double-tap`, `select-word`, `swipe-up`. |
| `show-cache.bat` | `run-as` listing of `cache/documents`, useful after reordering, rotating or rewriting. |
| `logcat-app.bat` | Last 400 log lines for the app PID, or recent `AndroidRuntime` crashes. |

### Build helpers

| Script | What it does |
| --- | --- |
| `__build.cmd` | Stops Gradle, runs `clean assembleRelease`, and writes `build\logs\__build.log`. |
| `__clean_hard.cmd` | Removes the project and user Gradle caches, then runs `gradlew clean`. Writes `build\logs\__clean_hard.log`. |
| `__clear_logs.cmd` | Deletes the files in `build\logs` and any legacy root logs. |
| `__verify_apk.cmd [apk]` | Verifies the signature with `jarsigner` and `apksigner`. Defaults to `build\app\outputs\apk\release\app-arm64-v8a-release.apk`. |
| `__keystore_generate.cmd` | Runs `__keystore_generate.js` to create or verify `keystore.properties` and the keystore it points at. |

Generated Gradle output lives under `<repo>\build\app`, and release APKs/AABs
are mirrored to `<repo>\build\release` after `assembleRelease` or
`bundleRelease`.

### Manual scenarios

`manual-testing.bat` drives this checklist:

1. **Open a PDF** - `sample.pdf` shows 14 pages and the page indicator.
2. **Double-tap zoom** - glides from 100% to 200%, 400%, 600%, then back to
   100%. Every intermediate value is allowed.
3. **Pinch zoom** - two-finger spread clamps at 600%, pinch together clamps at
   100%. Single-finger scrolling still works while zoomed.
4. **Text selection** - long-press a word, drag, then Copy / Select all / Close.
5. **Reorder pages** - overflow menu -> Reorder pages -> `1-3,7,9-e,3` on
   `sample.pdf` produces an 11-page `sample (reordered).pdf`. `show-cache.bat`
   should show the new name; the old cache file is gone. Pages not listed are
   deleted, so the result keeps only page 1-3, 7, 9-14 and then page 3 again.
6. **Rotate 180°** - overflow -> Rotate 180° -> keep `1-e` to turn every page
   upside down, or enter `2,4` to rotate only those pages.
7. **Linearize** - overflow -> Linearize -> qpdf writes
   `... (linearized).pdf`. The first 8 KiB of the file should contain
   `/Linearized`; the new copy opens with the same page count.
8. **Compact** - overflow -> Compact -> MuPDF writes
   `... (compact).pdf` with unused objects removed and streams, fonts and
   images compressed. This is separate from Linearize because MuPDF 1.28 cannot
   linearize.
9. **Encrypted PDF** - open `dummy-encrypted.pdf`, password `secret`. Reorder,
   rotate, linearize or compact it; the replacement opens without a password.
10. **TXT monospace** - open `notes.txt`, `README.nfo` or `book.text`, then
    overflow -> Monospace: on/off. All three should reflow.
11. **EPUB reflow** - open `alice.epub`; the text fills the reader width and
    zoom enlarges the layout.
12. **Gutenberg samples** - open a book from `gutenberg/`: EPUB, plain text and
    HTML should all render and reflow.
13. **Page indicator** - scroll to the end of `sample.pdf`. The indicator should
    change to the last page as soon as that page occupies most of the screen,
    not after the previous page has completely left.
14. **Rotation / process death** - rotate while reading, then enable
    "Don't keep activities" in Developer options and reopen. The file, page and
    monospace state should be restored.

### Gesture notes

`gestures.ps1` sends real Linux multitouch events, because adb cannot press two
fingers through `input`. That needs `adb root` (the script runs it for you),
which is available on emulators but usually not on retail phones.

Defaults are tuned for the `qpdf_full` AVD (1080x2340, input device
`/dev/input/event2`). On another device:

```bat
powershell -ExecutionPolicy Bypass -File tools\gestures.ps1 pinch-out -ScreenWidth 1440 -ScreenHeight 3120
adb shell getevent -lp
```

Use the event device whose name contains `multi_touch`.

The `open-fixture.bat` route uses the app's own FileProvider: it restarts the
app, copies the file into `cache/documents` through `run-as`, then sends the
matching `content://com.karako.pdfr.fileprovider/...` URI. This avoids the
scoped-storage trap where Android refuses to grant a shell-issued MediaStore
URI. If you have a release build installed instead (no `run-as`), the script
falls back to MediaStore; some Android builds reject that grant, in which case
use the folder button inside the app and pick `/sdcard/Download/pdfr-fixtures/...`
manually.

## Test-fixture generator

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

### Output

| File | Contents |
| --- | --- |
| `sample.pdf` | 14 letter-size pages labelled `Sample page 1` ... `Sample page 14`. |
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

### Options

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

### Used in testing

- `adb.exe` pushes the fixtures to the device.
- `tools\push-fixtures.bat` runs the generator and copies the results to
  `/sdcard/Download/pdfr-fixtures/`.
- The instrumented tests generate their own tiny PDFs at runtime instead of
  reading these files.

## Creating the test AVD

`create-test-avd.bat` creates an AVD named `qpdf_full` on a fresh machine:

```bat
tools\create-test-avd.bat
```

It looks for the SDK in `ANDROID_SDK_ROOT`, `ANDROID_HOME` or
`%LOCALAPPDATA%\Android\Sdk`, installs
`system-images;android-35;default;x86_64` when needed, then creates a Pixel 5
AVD with 1536 MB RAM and a 512 MB SD card. Optional arguments override the name
and RAM:

```bat
tools\create-test-avd.bat qpdf_full 1536
```

Re-running the script recreates the AVD with the same name.
