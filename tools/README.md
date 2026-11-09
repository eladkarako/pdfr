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
| `PDFR_AVD` | `pdfr_full` | AVD to start (override if your AVD has another name) |
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
| `create-test-avd.bat [name] [ram-mb]` | Creates the `pdfr_full` API 35 x86_64 AVD with 1536 MB RAM. Installs the system image if it is missing. Safe to re-run. |
| `start-emulator.bat [avd]` | Starts the AVD without a snapshot and waits until `sys.boot_completed`. |
| `stop-emulator.bat` | `adb emu kill`. |
| `install-debug.bat [abi]` | Builds `:app:assembleDebug`, detects the device ABI and installs the matching split APK. |
| `run-unit-tests.bat` | Runs the JVM tests (`:app:testDebugUnitTest`) and prints the report path. |
| `run-instrumented-tests.bat [class]` | Builds app + test APKs, installs both, and runs `AndroidJUnitRunner` on the device. Optional class filter. |
| `push-fixtures.bat` | Generates the fixtures and downloads a few public-domain Project Gutenberg books (EPUB/TXT/HTML) into `gutenberg/`. Then pushes everything to `/sdcard/Download/pdfr-fixtures/`. |
| `open-fixture.bat <name> [mime]` | Restarts PDFr, stages a generated fixture inside its cache with `run-as`, then sends the app its own FileProvider URI. Needs the debug build. Falls back to a MediaStore `ACTION_VIEW` if the app is not debuggable. |
| `manual-testing.bat` | Interactive menu for the scenarios below. |
| `gestures.ps1 <gesture>` | Raw multitouch/tap helpers: `pinch-out`, `pinch-in`, `double-tap`, `select-word`, `swipe-up`. |
| `zoom-probe.ps1 <action>` | Records `before/down/mid/late/settled` screenshots while pinching (`pinch-out`, `pinch-in`), panning with two fingers (`pan`) or double-tapping (`double-tap`), then scores how much the reading area changed between `late` and `settled`. `-CenterX`/`-CenterY` choose the midpoint. Writes to `build\zoom-probe\` by default. |
| `reader-actions.ps1 <action>` | Single-finger reader checks: `long-press`, `tap`, `select-all`, `copy`, `menu`, `info`, `metadata`, `metadata-apply`, `text-size`, `text-size-apply`, `toggle-single-page`, `toggle-line-wrap`, `toggle-align-right`, `toggle-line-numbers`, `scroll`, `screenshot` and `list`. Finds menu buttons through `uiautomator` and writes PNGs to `build\reader-actions\`. |
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
   100%. Single-finger scrolling still works while zoomed. `zoom-probe.ps1`
   records the sequence for later inspection.
4. **Text selection** - long-press anywhere on a page to open the page menu,
   choose **Select all**, and check that every text block (including footers)
   is highlighted and that **Copy** appears. Tapping the page dismisses it.
   `reader-actions.ps1 -Action select-all` repeats this automatically.
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
10. **TXT reading** - open `notes.txt`, `README.nfo` or `book.text`. The custom
    plain-text view shows one continuous page, line numbers and wrapped lines;
    the overflow menu offers single page, line wrap, alignment, line numbers,
    text size and monospace. Long-press selects text across paragraphs with the
    system selection toolbar, so **Select all** and **Copy** work for the whole
    document. Text size is 5-40 pt and starts at 12 pt.
11. **Plain-text toggles** - turn single page off to page the text vertically;
    turn line wrap off to pan long lines horizontally; turn align right on to
    move both the text and the line-number gutter to the right side; turn line
    numbers off to hide the gutter. Options survive rotation and the next file.
12. **EPUB reflow** - open `alice.epub`; the text fills the reader width and
    text size enlarges the layout. Pinch and double-tap are disabled for
    reflowable documents; use the menu's text size instead.
13. **Markdown** - open `guide.md`; headings, lists and paragraphs should
    reflow. `guide.markdown` also works through the temporary `.md` alias.
    Office files (DOCX/XLSX/PPTX/HWPX) are not supported.
14. **Source and config text** - open `config.yaml` and `main.c`; both should
    reflow as monospace plain text. Copy `notes.txt` to `notes.xyz` and open it:
    the unknown suffix must fall back to text detection. JSON, CSV, TSV, shell
    scripts and other source files use the same path.
15. **Images and PNM** - open `image.png` and `image.ppm`; each is a one-page
    document that stays sharp when zoomed.
16. **Metadata editor** - tap the circled **i**. Filename, extension and every
    metadata field the document already has are editable; dates use
    `yyyy-MM-ddThh:mm:ss` and have date/time pickers. Clearing a field removes
    its value. **Apply** rewrites the cache file and reloads it; **Cancel**
    discards the draft; dismissing the dialog keeps it for the next open.
    Share and Save as use the edited name and timestamp.
17. **Save as** - with any document open, tap the middle **Save** button, pick a
    destination, and confirm the copy has the same name, extension and content.
    Re-download the saved file and check that its modified time matches the
    source where the provider supports timestamps.
18. **Gutenberg samples** - open a book from `gutenberg/`: EPUB, plain text and
    HTML should all render and reflow.
19. **Page indicator** - scroll to the end of `sample.pdf`. The indicator should
    change to the last page as soon as that page occupies most of the screen,
    not after the previous page has completely left.
20. **Rotation / process death** - rotate while reading, then enable
    "Don't keep activities" in Developer options and reopen. The file, page and
    monospace state should be restored.

### Gesture notes

`gestures.ps1` sends real Linux multitouch events, because adb cannot press two
fingers through `input`. That needs `adb root` (the script runs it for you),
which is available on emulators but usually not on retail phones.

Defaults are tuned for the `pdfr_full` AVD (1080x2340, input device
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

### Zoom regression probe

`zoom-probe.ps1` captures what the reader looks like before, during and after a
two-finger gesture. It records the same sequence used to verify the live zoom:

```bat
tools\start-emulator.bat
tools\install-debug.bat

:: Fixed-layout PDF: pinch around the title of page 3.
tools\open-fixture.bat sample.pdf
powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pinch-out -CenterY 0.17

:: Reflowable text: pinch, then pan with two fingers.
tools\open-fixture.bat notes.txt
powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pinch-out
powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pan

:: Back to 100% and sharpen.
powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pinch-in -StartGap 780 -EndGap 120
```

Each run writes `-before`, `-down`, `-mid`, `-late` and `-settled` PNGs to
`build\zoom-probe\`. Check that:

- `down` still shows the old size, while `mid` already shows the page enlarged
  under the fingers (live resize, not a snap after the gesture);
- the touched point stays in place between `down` and `mid`;
- `late` and `settled` score as stable (mean under 1.5, fewer than 2% of sampled
  pixels changed): the debounced bitmap/reflow landed at exactly the live size.
  The toolbar and the fading page badge are excluded from the comparison;
- a two-finger `pan` moves the content without changing its size.

Reflowable documents repaginate when the deferred layout lands after the finger
lift, so `late` (still the old pages, scaled) and `settled` (new pagination)
differ by design. Use fixed-layout `sample.pdf` for the no-jump check; for
reflowable text, confirm that the content under the midpoint stays put during
the gesture and that scrolling afterwards feels normal.

Use `-SkipRoot` when adbd is already root (the usual emulator state). Plain
`adb root` can restart adbd, resize the viewport for a frame and trigger an
extra reflow, which looks like a jump between `before` and `down`.

Other options: `-CenterX 0.3`, `-CenterY 0.17`, `-StartGap`, `-EndGap`,
`-Gap`, `-StartY`, `-EndY`, `-Steps`, `-SettleMs`, `-OutDir`, `-Prefix`,
`-EventDevice`, `-ScreenWidth` and `-ScreenHeight`. Moving `-CenterX` away from
0.5 is the quickest way to confirm a pinch anchors on the midpoint between the
fingers instead of drifting towards the starter finger. The same actions are
available from the `manual-testing.bat` menu as option 11.

### Reader action checks

`reader-actions.ps1` covers the single-finger side of the reader: the
long-press page menu, Select all/Copy, the overflow menu, the document-info and
metadata dialogs, text size, the plain-text toggles, and scrolling.

```bat
tools\open-fixture.bat sample.pdf

:: Long-press over text, then inspect the page menu.
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action long-press

:: Select the whole page, then copy it.
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action select-all
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action copy -Prefix copied

:: Overflow menu, document info, and the editable metadata dialog.
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action menu
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action info -Prefix sample-info
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action metadata -Prefix sample-metadata

:: Text size and the plain-text toggles (open notes.txt or book.epub first).
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action text-size
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action toggle-single-page
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action toggle-line-wrap
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action toggle-align-right
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action toggle-line-numbers

:: Scroll and take a plain screenshot.
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action scroll
powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action screenshot
```

The menu actions read `uiautomator dump` and tap the visible **Select all**,
**Copy**, **Document info**, **Text size** or toggle label, so they do not
hard-code a pixel position. If a build changes the labels, `-Action list` prints
every visible text and content description. Override the press point with
`-X`/`-Y` and the overflow button with `-MenuX`/`-MenuY`; both are screen
fractions. `metadata-apply` and `text-size-apply` confirm the dialog and capture
the result after the reload.

Screenshots and the dumped `window.xml` land in `build\reader-actions\`. Check
that:

- the long-press menu shows only **Select all** and nothing is highlighted before
  it is tapped;
- after Select all, every text line on the page is highlighted and only **Copy**
  remains;
- Copy empties the clipboard toast and dismisses the menu;
- on plain text the native selection toolbar can select across paragraphs and
  the line numbers are never copied;
- the metadata editor keeps its draft when dismissed and shows Apply/Cancel at
  the top, and Apply reloads with the edited name;
- the text-size dialog stays inside 5-40 pt and applies to EPUB/Markdown but not
  to a PDF;
- the info and metadata dialogs scroll and never cover more than three quarters
  of the screen;
- the page indicator starts dim and fades to nearly invisible after about five
  seconds, then returns on the next scroll.

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
| `guide.md` | Markdown headings, lists, a link, a code block, a quote and a long paragraph. |
| `guide.markdown` | Same content with a suffix MuPDF does not know, to exercise the temporary `.md` alias. |
| `config.yaml` | A small YAML file, opened through the temporary `.txt` text path. |
| `main.c` | A small C source file, also opened as plain text. |
| `image.png` | A 480x320 solid-colour image, one page when opened. |
| `image.ppm` | A 480x320 binary PPM, exercising the PNM/PAM handler. |
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

`create-test-avd.bat` creates an AVD named `pdfr_full` on a fresh machine:

```bat
tools\create-test-avd.bat
```

It looks for the SDK in `ANDROID_SDK_ROOT`, `ANDROID_HOME` or
`%LOCALAPPDATA%\Android\Sdk`, installs
`system-images;android-35;default;x86_64` when needed, then creates a Pixel 5
AVD with 1536 MB RAM and a 512 MB SD card. Optional arguments override the name
and RAM:

```bat
tools\create-test-avd.bat pdfr_full 1536
```

Re-running the script recreates the AVD with the same name.
