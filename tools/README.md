# PDFr tools

Windows helpers for building, installing, testing and documenting PDFr, plus
the generators for the manual-test documents.

Every batch file and script works from the repository root and from `tools\`.
They locate the repository relative to themselves, and the ones that call
Gradle enter the root before building, so neither the starting directory nor a
`..\` in a relative path matters.

Each chapter below covers one tool: how to run it, the variations worth knowing,
and what it leaves behind.

## Before the first run

Make sure `ANDROID_SDK_ROOT` is set (or the SDK is in the default
`%LOCALAPPDATA%\Android\Sdk`). Everything can be overridden:

| Variable | Default | Meaning |
| --- | --- | --- |
| `ANDROID_SDK_ROOT` / `ANDROID_HOME` | auto-detected | Android SDK location |
| `ADB` | `%ANDROID_SDK_ROOT%\platform-tools\adb.exe` | adb binary |
| `EMULATOR` | `%ANDROID_SDK_ROOT%\emulator\emulator.exe` | emulator binary |
| `PDFR_AVD` | `pdfr_full` | AVD to start (override if your AVD has another name) |
| `PDFR_FIXTURES` | `<repo>\build\test-fixtures` | generated fixture folder |

### Quick start

```bat
tools\create-test-avd.bat
tools\start-emulator.bat
tools\install-debug.bat
tools\push-fixtures.bat
tools\manual-testing.bat
```

### Where the tools write

| Path | Contents |
| --- | --- |
| `build\app\outputs\apk\` | APKs and the instrumented test APK |
| `build\test-fixtures\` | generated documents, Gutenberg books and the colour sampler |
| `build\reader-actions\` | `reader-actions.js` screenshots and `window.xml` |
| `build\reader-checks\` | `reader-checks.bat` screenshots and `results.txt` |
| `build\zoom-probe\` | `zoom-probe.js` before/down/mid/late/settled PNGs |
| `build\promo\` | promo screenshots, MP4 and poster |
| `build\logs\` | output of the `__*.cmd` build helpers |

Generated Gradle output lives under `<repo>\build\app`, and release APKs/AABs
are mirrored to `<repo>\build\release` after `assembleRelease` or
`bundleRelease`.

## AVDs

### `create-test-avd.bat [name] [ram-mb]`

Creates the phone emulator used by the screenshots, probes and instrumented
tests.

```bat
tools\create-test-avd.bat
```

Variations:

```bat
:: A larger AVD for a machine with more RAM.
tools\create-test-avd.bat pdfr_full 4096

:: Pin an older API or image when a specific level is needed.
node tools\create-test-avd.js -Name pdfr_api36 -Api 36
```

It looks for the SDK in `ANDROID_SDK_ROOT`, `ANDROID_HOME` or
`%LOCALAPPDATA%\Android\Sdk`, then asks `sdkmanager` for the newest
`default`/`x86_64` system image (installed or available) and installs it when
needed. No API level is hard-coded, so the AVD follows the current SDK
automatically. It then creates a Pixel 5 AVD with 1536 MB RAM and a 512 MB SD
card. `-Api`, `-Abi`, `-Tag` and `-Device` override the image and hardware
profile.

Result: an AVD named `pdfr_full` (or the name you passed). Re-running the script
recreates the AVD with the same name, so it is safe to run again after a broken
boot.

### `create-tv-avd.bat [name] [ram-mb]`

Creates the Android TV emulator used for the D-pad and leanback checks.

```bat
tools\create-tv-avd.bat
tools\start-emulator.bat pdfr_tv
tools\run-instrumented-tests.bat com.karako.pdfr.TvSupportInstrumentedTest
```

It asks `sdkmanager` for the newest `android-tv`/`x86_64` system image (currently
`android-36`, because Android TV does not publish an x86_64 image for every
release) and installs it when needed (a large download on the first run). It
then creates a 1080p TV AVD named `pdfr_tv` and enables the D-pad and a keyboard
in the generated config. Pass `-SkipInstall` to use only an already-installed
image, or `-Name`/`-RamMb` to override the defaults:

```bat
node tools\create-tv-avd.js -Name pdfr_tv -RamMb 2048
```

Result: a TV AVD that runs the same APK as a phone. The leanback launcher entry
is an extra intent filter and the TV features are declared optional, so the
accessibility tests run unchanged: `hw.keyboard=yes` gives them the Tab key.

Both AVD scripts treat `sdkmanager`/`avdmanager` output on stderr as normal
text, so warnings such as the newer SDK tools' `sdkmanager` deprecation notice
do not abort the run.

### `start-emulator.bat [avd]`

Starts an AVD without a snapshot and waits until Android has finished booting.

```bat
tools\start-emulator.bat
tools\start-emulator.bat pdfr_tv
```

The AVD defaults to `PDFR_AVD` (`pdfr_full`). A missing AVD produces the list of
installed AVDs instead of a boot loop.

Result: an emulator window in the background, and a command prompt that only
returns once `sys.boot_completed` is `1`. The first boot after
`create-test-avd.bat` takes the longest.

### `stop-emulator.bat`

```bat
tools\stop-emulator.bat
```

Sends `adb emu kill` to the running emulator.

Result: the emulator window closes and adb reports no device. Use it after the
instrumented tests or a capture run to release the RAM.

## Build and install

### `install-debug.bat [abi]`

Builds and installs the debug APK that the scripts drive.

```bat
tools\install-debug.bat
tools\install-debug.bat arm64-v8a
```

Without an argument the script asks the connected device for
`ro.product.cpu.abi` and installs the matching split. Pass `x86_64`,
`arm64-v8a`, `armeabi-v7a` or `x86` to override it.

Result: `:app:assembleDebug` runs from the repository root, the matching
`app-<abi>-debug.apk` is installed with `adb install -r`, and the app is ready
for `open-fixture.bat` and the reader scripts. It works when started from
`tools\` as well as from the root.

### `__build.cmd`

```bat
tools\__build.cmd
```

Stops the Gradle daemon, runs `clean assembleRelease`, and writes the full log
to `build\logs\__build.log`.

Result: release APKs/AABs in `build\app\outputs\` and a mirror in
`build\release\`. The console shows the same output as the log.

### `__clean_hard.cmd`

```bat
tools\__clean_hard.cmd
```

Removes the project and user Gradle caches, then runs `gradlew clean`. The log
lands in `build\logs\__clean_hard.log`.

Result: the next build downloads dependencies again. Use it when a build fails
in a way that `clean` alone does not fix; expect a slow first build afterwards.

### `__clear_logs.cmd`

```bat
tools\__clear_logs.cmd
```

Deletes the files in `build\logs` and any logs left over from the old layout.

Result: an empty log folder, ready for the next `__build.cmd`,
`__verify_apk.cmd` or `__clean_hard.cmd` run.

### `__verify_apk.cmd [apk]`

```bat
tools\__verify_apk.cmd
tools\__verify_apk.cmd build\app\outputs\apk\release\app-arm64-v8a-release.apk
```

Verifies the signature with `jarsigner` and `apksigner`. Without an argument it
checks `build\app\outputs\apk\release\app-arm64-v8a-release.apk`. The script
finds the newest installed `build-tools`, skips a checker with a warning when it
is unavailable, and writes `build\logs\__verify_apk.log`.

Result: a non-zero exit code and a readable reason when the APK is unsigned or
the certificate is wrong; `[OK]` when both checkers agree.

### `__keystore_generate.cmd`

```bat
tools\__keystore_generate.cmd
```

Runs `__keystore_generate.js` to create or verify `keystore.properties` and the
keystore it points at. Node is looked up on `PATH` first, then in
`tools\node.exe` (where `make_test_files.cmd` downloads it).

Result: a release keystore and a matching `keystore.properties`, or a check of
the existing pair. Keep both files out of version control.

### `build-qpdf-android.js`

Builds the static qpdf library and the JNI bridge for Android, then refreshes
`app\src\main\jniLibs`.

```bat
node tools\build-qpdf-android.js
node tools\build-qpdf-android.js -Abis arm64-v8a -Clean
```

| Option | Default | Meaning |
| --- | --- | --- |
| `-Abis` | all four | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` |
| `-QpdfVersion` | `12.4.2` | qpdf source release |
| `-JpegVersion` | `3.2.1` | libjpeg-turbo source release |
| `-AndroidPlatform` | `android-24` | minimum API used by the NDK build |
| `-Ndk` | `ANDROID_NDK_ROOT` | NDK location |
| `-Sdk` | `ANDROID_SDK_ROOT` / `ANDROID_HOME` | SDK location |
| `-Clean` | off | rebuild the work tree from scratch |
| `-SkipDownload` | off | reuse the sources in `build\qpdf\src` |

Result: a stripped `libqpdfjni.so` per ABI in
`app\src\main\jniLibs\<abi>\`, plus the CMake work tree under `build\qpdf\`.
Rebuild the app afterwards; the JNI bridge is what exposes qpdf to Kotlin.

## Tests

### `run-unit-tests.bat [class[#method]]`

Runs the JVM tests and prints the report path.

```bat
tools\run-unit-tests.bat
tools\run-unit-tests.bat com.karako.pdfr.data.AppSettingsTest
tools\run-unit-tests.bat com.karako.pdfr.data.PageRangesTest#parsesRanges
```

The optional argument is passed to Gradle as `--tests`, so a class or a single
`Class#method` both work. No device is needed.

Result: `:app:testDebugUnitTest` runs from the repository root,
`build\app\reports\tests\testDebugUnitTest\index.html` holds the report, and the
script exits non-zero on a failure.

### `run-instrumented-tests.bat [class] [--start-emulator]`

Builds the app and test APKs, installs both, and runs `AndroidJUnitRunner` on
the device.

```bat
tools\run-instrumented-tests.bat
tools\run-instrumented-tests.bat com.karako.pdfr.AccessibilityInstrumentedTest
tools\run-instrumented-tests.bat com.karako.pdfr.TvSupportInstrumentedTest --start-emulator
```

The class filter maps to `am instrument -e class`. `--start-emulator` starts
the AVD only after the build, so Gradle and the emulator do not compete for
RAM; without it, the script fails early with a hint when no device is attached.

The build runs from the repository root, so the script also works when started
in `tools\`. It inspects the runner's own report (`FAILURES!!!`,
`INSTRUMENTATION_FAILED`, `INSTRUMENTATION_ABORTED`, `Process crashed`) because
`adb shell am instrument` returns 0 even when a test fails.

Result: `[OK] Instrumented tests passed.` with exit code 0, or `[FAIL]` with a
suggestion to re-run a single class. The APKs stay installed on the device.

### `reader-checks.bat [-Section ...]`

Automated on-device regression checks for the reader.

```bat
tools\reader-checks.bat
tools\reader-checks.bat -Section text
tools\reader-checks.bat -Section empty,text,selection,metadata,exit
tools\reader-checks.bat -Section gestures -SkipRoot
```

Sections: `empty`, `text`, `selection`, `metadata`, `gestures`, `exit`
(default `all`). It checks the disabled toolbar with no document, plain-text
defaults and toggles, native selection plus the Copy/Copy all touch menu, the
editable info dialog, and pinch behaviour. Copy all is checked through the
clipboard against `long.txt`, which is taller than the viewport.

Reads Compose through `uiautomator dump --windows` (the text context menu is its
own popup window, so the app and popup hierarchies are merged) and compares
screenshots for the pixel-only checks. `-SkipRoot` avoids an `adb root` restart
that can resize the viewport between screenshots; `-KeepOpen` leaves the app on
whatever the last check opened. Screen coordinates default to 1080x2340; use
`-ScreenWidth`, `-ScreenHeight` and `-EventDevice` on another device.

Result: `build\reader-checks\results.txt` plus the screenshots, and a non-zero
exit code on any failure. The gesture section needs adb root, exactly like
`zoom-probe.js`; the other sections also run on a retail device.

### `reader-actions.js <action>`

Drives the single-finger reader actions and captures screenshots. It sends
`adb shell input` events, so it needs no root and works on a normal device.

```bat
tools\open-fixture.bat sample.pdf
node tools\reader-actions.js -Action long-press
```

Actions, grouped by what they exercise:

| Group | Actions |
| --- | --- |
| Page menu and selection | `long-press`, `tap`, `select-all`, `copy` |
| Overflow menu and dialogs | `menu`, `menu-mirror`, `info`, `metadata`, `metadata-apply` |
| Text and plain-text toggles | `text-size`, `text-size-apply`, `toggle-single-page`, `toggle-line-wrap`, `toggle-align-right`, `toggle-line-numbers` |
| Settings | `settings`, `theme-system`, `theme-light`, `theme-dark`, `theme-amoled`, `language`, `language-hebrew`, `language-system` |
| Inspection | `scroll`, `screenshot`, `list` |

Useful variations:

```bat
:: Keep runs side by side.
node tools\reader-actions.js -Action info -Prefix sample-info

:: Tap somewhere else (screen fractions).
node tools\reader-actions.js -Action long-press -X 0.5 -Y 0.3

:: A phone with another toolbar layout.
node tools\reader-actions.js -Action menu -MenuX 0.93 -MenuY 0.06

:: List what the current screen exposes when a label cannot be found.
node tools\reader-actions.js -Action list

:: Switch the UI to any offered language, from any current UI language.
node tools\reader-actions.js -Action language -Language pt-BR

:: Clear the override and follow the system language again.
node tools\reader-actions.js -Action language -Language system
```

The menu actions find the visible **Select all**, **Copy**, **Document info**,
**Text size** or toggle label through `uiautomator`, so they do not hard-code a
pixel position. Theme actions match both the English and Hebrew labels. The
`language` action reads the combo's current caption to open it and then taps the
row by its native name, so it works after any earlier language switch; its
`-Language` values are `system`, `en`, `en-GB`, `he`, `ar`, `fr`, `fr-CA`, `de`,
`es`, `es-419`, `pt`, `pt-BR`, `ru`, `ja`, `zh-CN`, `zh-TW`, `ko` and `hi`.
`-ScreenWidth`/`-ScreenHeight` match the coordinates to the device.

Result: a PNG (and, for menu actions, the `window.xml` used to find the button)
in `build\reader-actions\`. Check that:

- the long-press menu shows only **Select all** and nothing is highlighted before
  it is tapped;
- after Select all, every text line on the page is highlighted and only **Copy**
  remains;
- Copy empties the clipboard toast and dismisses the menu;
- on plain text the touch menu offers only Copy and Copy all, the line numbers
  are never copied, and Copy all copies the whole document even when the
  selection is a few words;
- the metadata editor keeps its draft when dismissed and shows Apply/Cancel at
  the top, and Apply reloads with the edited name;
- the text-size dialog stays inside 5-40 pt and applies to EPUB/Markdown but not
  to a PDF;
- the info and metadata dialogs scroll and never cover more than three quarters
  of the screen;
- the page indicator starts dim and fades to nearly invisible after about five
  seconds, then returns on the next scroll;
- the settings dialog lists the four themes in the required order and switches
  the palette as soon as a row is tapped;
- switching to עברית mirrors the toolbar, menu and dialogs while plain text and
  PDF pages keep their own direction;
- the language combo aligns the English name, code and native name columns, and
  a switch to any of the other languages reloads with translated captions
  (Japanese and Chinese keep their own left-to-right layout).

### `zoom-probe.js <action>`

Records what the reader looks like before, during and after a two-finger
gesture, then scores how much the reading area changed between `late` and
`settled`.

```bat
tools\start-emulator.bat
tools\install-debug.bat

:: Fixed-layout PDF: pinch around the title of page 3.
tools\open-fixture.bat sample.pdf
node tools\zoom-probe.js -Action pinch-out -CenterY 0.17

:: Reflowable text: pinch, then pan with two fingers.
tools\open-fixture.bat notes.txt
node tools\zoom-probe.js -Action pinch-out
node tools\zoom-probe.js -Action pan

:: Back to 100% and sharpen.
node tools\zoom-probe.js -Action pinch-in -StartGap 780 -EndGap 120
```

Actions: `pinch-out`, `pinch-in`, `pan`, `double-tap`.

| Option | Default | Meaning |
| --- | --- | --- |
| `-CenterX` / `-CenterY` | `0.5` / `0.45` | gesture midpoint, as screen fractions |
| `-StartGap` / `-EndGap` | `280` / `620` | finger distance for a pinch |
| `-Gap` | `420` | finger distance for a pan |
| `-StartY` / `-EndY` | `1700` / `900` | pan start and end |
| `-Steps` | `8` | intermediate events |
| `-SettleMs` | `700` | wait for the deferred render |
| `-LateDelayMs` | `400` | delay before the `late` frame |
| `-OutDir` / `-Prefix` | `build\zoom-probe` / `zoom` | output location and name |
| `-EventDevice` | `/dev/input/event2` | multitouch device |
| `-ScreenWidth` / `-ScreenHeight` | `1080` / `2340` | device size |
| `-SkipRoot` | off | skip `adb root` when adbd is already root |

The pinch and pan actions use Linux multitouch events, which is the only way adb
can press two fingers at once. They need `adb root`, which works on an emulator
(and on debuggable engineering builds), not on a retail phone.

Result: `-before`, `-down`, `-mid`, `-late` and `-settled` PNGs in
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

Moving `-CenterX` away from 0.5 is the quickest way to confirm a pinch anchors
on the midpoint between the fingers instead of drifting towards the starter
finger. The same actions are available from `manual-testing.bat` as option 11.

### `gestures.js <gesture>`

Sends reader gestures to the connected device.

```bat
node tools\gestures.js pinch-out
node tools\gestures.js double-tap
```

Gestures: `pinch-out`, `pinch-in`, `double-tap`, `select-word`, `swipe-up`.
Options: `-EventDevice`, `-ScreenWidth`, `-ScreenHeight`, `-Steps`.

The pinch gestures use Linux multitouch events, which is the only way adb can
press two fingers at once. They need `adb root`, which works on an emulator (and
on debuggable engineering builds), not on a retail phone.

Coordinates default to a 1080x2340 phone/AVD. Override `-ScreenWidth` and
`-ScreenHeight` for another device. The multitouch device defaults to the
emulator's `/dev/input/event2`; use `adb shell getevent -lp` to find another:

```bat
node tools\gestures.js pinch-out -ScreenWidth 1440 -ScreenHeight 3120
adb shell getevent -lp
```

Use the event device whose name contains `multi_touch`.

Result: the gesture plays on the device. Nothing is written to disk; use
`zoom-probe.js` when you want screenshots and a stability score, or
`reader-actions.js` for the single-finger menu actions.

## Fixtures and device files

### `make_test_files.cmd`

Generates the documents used for manual testing into
`build\test-fixtures\` (`../build/test-fixtures/` from the script's point of
view).

```bat
tools\make_test_files.cmd
tools\make_test_files.cmd --out build\my-fixtures --pages 30 --no-gutenberg
```

Everything except the encrypted PDF is produced with Node's built-in modules.
The encrypted PDF needs `qpdf` on `PATH` or next to the script.
`make_test_files.cmd` downloads Node and qpdf into `tools\` when they are
missing from the folder or `PATH`, and uses `curl` and `7z`.

Options:

```text
--out DIR                output directory (default: <repo>/build/test-fixtures)
--pages N                number of pages in sample.pdf (default 14)
--password TEXT          password for dummy-encrypted.pdf (default secret)
--no-encrypted           skip dummy-encrypted.pdf
--no-gutenberg            skip the Project Gutenberg downloads
--gutenberg-timeout MS   per-download timeout in milliseconds (default 30000)
```

Output:

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
| `gutenberg/gutenberg-color.pdf` | Six colourful A4 pages built from Project Gutenberg excerpts, used for screenshots and promo recordings. |

The generated PDFs are real, text-extractable documents (base-14 Helvetica), so
selection, reordering, rotation and qpdf linearization all work on them.

Gutenberg fixtures:

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

Result: the documents above in the chosen folder. `push-fixtures.bat` copies
them to the device, and `open-fixture.bat` opens one by name.

### `fetch-gutenberg.js`

The standalone Node.js equivalent of the Gutenberg part of the generator. It
uses only the standard library.

```bash
node tools/make_test_files.js --out build/test-fixtures
node tools/fetch-gutenberg.js --out build/test-fixtures/gutenberg
```

Variations: `--list` prints the known books, `--only <slug>` downloads one
(`alice`, `pride`, `sherlock`, `yellow`). The JavaScript generator also
downloads the Gutenberg books itself unless you pass `--no-gutenberg`.

Result: the same `gutenberg/` folder and `SOURCES.txt`, without the full fixture
generator.

### `push-fixtures.bat`

Generates the fixtures, pushes them to the emulator and asks MediaStore to index
them.

```bat
tools\push-fixtures.bat
```

It runs `make_test_files.cmd` into `PDFR_FIXTURES` (default
`build\test-fixtures`), creates `/sdcard/Download/pdfr-fixtures/`, pushes the
folder (including `gutenberg/`) and broadcasts a media scan for every file.

Result: the fixtures are visible in the device's Downloads and in the app's
picker. The encrypted PDF password is `secret`. The script prints each pushed
name and ends with the `open-fixture.bat` hint.

### `open-fixture.bat <name-or-path> [mime-type]`

Restarts PDFr, stages a generated fixture inside its cache with `run-as`, then
sends the app its own FileProvider URI.

```bat
tools\open-fixture.bat sample.pdf
tools\open-fixture.bat notes.txt text/plain
tools\open-fixture.bat C:\docs\paper.pdf
```

The name is looked up in the current directory, the repository root, and
`PDFR_FIXTURES`, so both `sample.pdf` and
`build\test-fixtures\sample.pdf` work from the root or from `tools\`. The MIME
type is guessed from the extension unless you pass one.

The preferred route needs the debug build: it copies the file into
`cache/documents` through `run-as` and sends the matching
`content://com.karako.pdfr.fileprovider/...` URI. This avoids the scoped-storage
trap where Android refuses to grant a shell-issued MediaStore URI. If you have a
release build installed instead (no `run-as`), the script falls back to a
MediaStore `ACTION_VIEW`; some Android builds reject that grant, in which case
use the folder button inside the app and pick
`/sdcard/Download/pdfr-fixtures/...` manually.

Result: PDFr opens the fixture in the reader. Run it before any
`reader-actions.js`, `zoom-probe.js` or `reader-checks.bat` action that needs
a document.

### `show-cache.bat`

```bat
tools\show-cache.bat
```

`run-as` listing of `cache/documents`, including the per-import folders.

Result: the cache file names and sizes. Use it after reordering, rotating,
normalizing or fixing: the new copy should be there and the replaced one
should be gone.

### `logcat-app.bat`

```bat
tools\logcat-app.bat
```

Prints the last 400 log lines for the app PID, or recent `AndroidRuntime`
crashes when the process is not running.

Result: a readable excerpt on the console. Useful after a failed conversion, a
native qpdf/MuPDF error, or a crash that does not reach the UI.

## Manual walkthrough

### `manual-testing.bat`

Interactive menu for the scenarios below.

```bat
tools\push-fixtures.bat
tools\manual-testing.bat
```

The menu covers pushing fixtures, opening each format, zoom probes, selection,
the overflow mirror, promo capture, settings, every offered language, the TV
banner and the cache/log helpers. Choose a number and follow the prompt.

Result: the chosen scenario runs on the connected device. The checklist below is
the same route, in order, for a full manual pass:

1. **Open a PDF** - `sample.pdf` shows 14 pages and the page indicator.
2. **Double-tap zoom** - glides from 100% to 200%, 400%, 600%, then back to
   100%. Every intermediate value is allowed.
3. **Pinch zoom** - two-finger spread clamps at 600%, pinch together clamps at
   100%. Single-finger scrolling still works while zoomed. `zoom-probe.js`
   records the sequence for later inspection.
4. **Text selection** - long-press anywhere on a page to open the page menu,
   choose **Select all**, and check that every text block (including footers)
   is highlighted and that **Copy** appears. Tapping the page dismisses it.
   `reader-actions.js -Action select-all` repeats this automatically.
5. **Reorder pages** - overflow menu -> Reorder pages -> `1-3,7,9-e,3` on
   `sample.pdf` produces an 11-page `sample (reordered).pdf`. `show-cache.bat`
   should show the new name; the old cache file is gone. Pages not listed are
   deleted, so the result keeps only page 1-3, 7, 9-14 and then page 3 again.
6. **Rotate 180°** - overflow -> Rotate 180° -> keep `1-e` to turn every page
   upside down, or enter `2,4` to rotate only those pages.
7. **Normalize** - overflow -> Normalize -> choose options and apply. qpdf
   writes `... (normalized).pdf`; the new copy opens with the same page count.
   With **Linearize** selected the first 8 KiB of the file should contain
   `/Linearized`; with **PDF/A-2b** selected it should contain `/GTS_PDFA1`
   and `pdfaid:part`. Try a run with image optimisation and JPEG quality 70 to
   confirm the images still render.
8. **Fix PDF** - overflow -> Fix PDF -> qpdf writes `... (fixed).pdf` and opens
   it with the same content. With no document open, the overflow menu offers
   only **Fix PDF**; choosing it opens the picker and repairs the selected PDF
   as soon as it loads. The entry is absent for plain text, EPUB, images and
   other non-PDF documents.
9. **Encrypted PDF** - open `dummy-encrypted.pdf`, password `secret`. Reorder,
   rotate, normalize or fix it; the replacement opens without a password.
10. **TXT reading** - open `notes.txt`, `README.nfo` or `book.text`. The custom
    plain-text view shows one continuous page, line numbers and wrapped lines;
    the overflow menu starts with **Copy all**, then a divider, then the
    settings: single page, line wrap, alignment, line numbers, text size and
    monospace. Long-press selects text across paragraphs and shows a touch menu
    with **Copy** (the selection, greyed out when empty) and **Copy all** (the
    whole document, whatever is selected); there is no **Select all**. Text
    size is 5-40 pt and starts at 12 pt.
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
16. **Metadata editor** - tap the **i** button. Filename, extension and every
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
21. **Overflow mirror** - open any document and tap the overflow button. The
    first four entries are the toolbar actions in the same left-to-right order:
    **Share**, **Document info**, **Open**, **Save as**, each with its
    leading icon, followed by a divider and the document actions. With no
    document open the mirrored entries are disabled except **Open**.
    `reader-actions.js -Action menu-mirror` captures the menu.
22. **Promo capture** - `capture-promo.js` rebuilds, generates the colourful
    Gutenberg sampler, records the screenshots and MP4, and composes the poster.
    Check that `gutenberg-color.pdf` opens with six A4 pages of readable text
    and that the poster shows the chosen screenshots without stretching.
23. **Page size** - open `sample.pdf` and choose overflow -> **Page size…**.
    Keep the default `1-e` and A4: the new `sample (page size).pdf` opens with
    every page box at 595.28 x 841.89 pt while the text stays where it was.
    Repeat with `2-3` and A5 to resize only those pages, and with a custom
    size in each unit (mm, cm, in, pt, px, em) to check the point summary.
    Open `image.png`, choose **Page size** and confirm the image is fitted and
    centred on the chosen sheet. The entry is absent for EPUB, CBZ and text.
24. **Exit** - the overflow menu ends with a divider, **Settings…** and **Exit**,
    with or without a document open. Choosing Exit removes the task from recents
    and stops the process: `adb shell pidof com.karako.pdfr` prints nothing
    afterwards.
25. **Theme** - overflow -> **Settings…** -> **Theme**. The four rows are ordered
    **Same as system**, **Light**, **Dark**, **AMOLED black**. A row applies
    immediately: Light stays light with the device in dark mode, Dark uses grey
    surfaces, and AMOLED black uses pure black backgrounds and surfaces. The
    status/navigation bar icons stay readable in each mode. Reopen the app and
    the choice is still there. `reader-actions.js -Action theme-amoled`
    captures the result.
26. **Language** - overflow -> **Settings…** -> **Language**. The language row is
    a closed combo box: it shows the current choice, and opening it lists every
    language in three aligned columns - English name, resource code and native
    name - so a row can be recognised whichever UI language is active. Hebrew
    shows the code `iw` because that is the folder Android resolves, but the
    modern `he` tag is what gets stored. Choosing **עברית** reloads the activity
    and the whole UI, menus and dialogs are Hebrew with a right-to-left layout;
    the toolbar keeps Share → Info → Open → Save → Menu left-to-right, and
    `notes.txt` still starts left-aligned unless **Align right** is on. The same
    reload happens for **English**, **English (UK)**, **Arabic**, **French**,
    **French (Canada)**, **German**, **Spanish**, **Spanish (LatAm)**,
    **Portuguese (PT)**, **Portuguese (BR)**, **Russian**, **Japanese**,
    **Chinese (Simp.)**, **Chinese (Trad.)**, **Korean** and **Hindi**.
    **System default** clears the override. `reader-actions.js -Action language
    -Language ja` (any tag from the list, plus `system`) drives the combo from
    the current UI language; `-Action language-hebrew` and `-Action
    language-system` remain shortcuts, and `manual-testing.bat` option 19
    prompts for any tag.
27. **Keyboard and mouse** - with a hardware keyboard, Tab moves through the
    toolbar in Share, Info, Open, Save, Menu order in every language (the
    toolbar does not mirror), and then into the reader and dialogs; Shift+Tab
    reverses it. The focused button gets a primary-coloured ring, hovering with
    a mouse shows the same ring, and pressing shows a coloured ripple. Arrow keys
    work in the settings and metadata dialogs.
28. **Android TV** - install the same APK on a TV or the TV emulator. PDFr
    appears in the leanback launcher with its 320x180 banner, the D-pad moves
    focus between toolbar buttons and menu rows, and the settings dialog scrolls
    with the remote. `make-tv-banner.js` regenerates the banner.

## Capture

### `capture-promo.js`

Produces the screenshots, the short MP4 and the poster used for release notes
and store listings.

```bat
:: Build, start the AVD, capture everything, stop the AVD.
node tools\capture-promo.js -StartEmulator

:: Reuse the current build and connected device, skip the video.
node tools\capture-promo.js -SkipBuild -SkipVideo

:: Leave the emulator running for another capture.
node tools\capture-promo.js -KeepEmulator
```

| Option | Meaning |
| --- | --- |
| `-OutDir` | output folder, default `build\promo` |
| `-StartEmulator` | start the AVD after the build, then stop it at the end |
| `-SkipBuild` | reuse the existing debug APK |
| `-SkipVideo` | skip the scrolling MP4 |
| `-KeepEmulator` | do not stop an emulator that the script started |

It builds the debug APK before it touches the emulator, then generates
fixtures, installs the app and drives the reader through a small tour. The
capture uses `gutenberg/gutenberg-color.pdf`, a six-page A4 sampler built by
`make_test_files.js` from public-domain Gutenberg excerpts. Its large colour
areas survive video compression and make the reading UI easy to see in a
thumbnail. `SOURCES.txt` records the source eBooks.

Output in `build\promo\`:

| File | Contents |
| --- | --- |
| `01-empty.png` | Empty state with the document actions disabled. |
| `02-pdf-cover.png`, `03-pdf-colour.png` | Two pages of the colour sampler. |
| `04-overflow.png` | The overflow menu with the mirrored toolbar entries. |
| `05-info.png` | The document-info editor. |
| `06-epub.png` | A reflowable EPUB. |
| `pdfr-promo.mp4` | Scrolling and the overflow menu, scaled to 720 px wide. |
| `poster.png` | 3-4 screenshots composed into a shareable poster. |
| `README.txt` | A short index of the captured assets. |

The same steps are available from `manual-testing.bat` as options 13 and 14.

### `make-poster.js`

Composes 3-4 screenshots into `poster.png` with a dark gradient, rounded cards
and a title, using the bundled PNG and raster helpers (no ImageMagick, ffmpeg
or System.Drawing).

```bat
node tools\make-poster.js -OutDir build\promo
node tools\make-poster.js -OutDir build\promo -Images 02-pdf-cover.png,04-overflow.png,06-epub.png
node tools\make-poster.js -OutDir build\promo -Columns 1 -Width 1000
```

| Option | Default | Meaning |
| --- | --- | --- |
| `-OutDir` | `build\promo` | folder with the screenshots |
| `-Images` | preferred four | comma-separated or array of file names |
| `-OutputName` | `poster.png` | output file |
| `-Title` / `-Subtitle` | `PDFr` / `Read anything.` | heading text |
| `-Width` | `1600` | canvas width |
| `-Columns` | `2` | `1` gives a vertical strip |
| `-Background` / `-BackgroundEnd` | `#0F1720` / `#22384D` | gradient |
| `-Accent` / `-Foreground` | `#E0A526` / `#F5F1E8` | highlight and text colours |

Without `-Images` it picks `02-pdf-cover`, `03-pdf-colour`, `04-overflow` and
`06-epub`, falling back to the PNGs that exist.

Result: `poster.png` in the chosen folder, with the screenshots cropped to a
common card size rather than stretched.

### `make-tv-banner.js`

Draws the 320x180 Android TV banner (document sheet, `PDFr`, `Read anything`)
with the bundled PNG and raster helpers.

```bat
node tools\make-tv-banner.js
node tools\make-tv-banner.js -OutFile C:\tmp\banner.png
```

Without `-OutFile` it writes
`app\src\main\res\drawable-nodpi\tv_banner.png`.

Result: the banner used by the leanback launcher. Re-run it after changing the
app's colours or name, then rebuild the APK.
