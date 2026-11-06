# PDFr helper scripts

Windows batch/PowerShell helpers for building, installing and testing PDFr.
Run them from anywhere; they locate the repository relative to themselves.

Before the first run, make sure `ANDROID_SDK_ROOT` is set (or the SDK is in
the default `%LOCALAPPDATA%\Android\Sdk`). Everything can be overridden:

| Variable | Default | Meaning |
| --- | --- | --- |
| `ANDROID_SDK_ROOT` / `ANDROID_HOME` | auto-detected | Android SDK location |
| `ADB` | `%ANDROID_SDK_ROOT%\platform-tools\adb.exe` | adb binary |
| `EMULATOR` | `%ANDROID_SDK_ROOT%\emulator\emulator.exe` | emulator binary |
| `PDFR_AVD` | `pdfr_full` | AVD to start (override if your AVD has another name) |
| `PDFR_FIXTURES` | `<repo>\build\test-fixtures` | generated fixture folder |

## The short version

```bat
scripts\start-emulator.bat
scripts\install-debug.bat
scripts\push-fixtures.bat
scripts\manual-testing.bat
```

## Scripts

| Script | What it does |
| --- | --- |
| `start-emulator.bat [avd]` | Starts the AVD without a snapshot and waits until `sys.boot_completed`. |
| `stop-emulator.bat` | `adb emu kill`. |
| `install-debug.bat [abi]` | Builds `:app:assembleDebug`, detects the device ABI and installs the matching split APK. |
| `run-unit-tests.bat` | Runs the JVM tests (`:app:testDebugUnitTest`) and prints the report path. |
| `run-instrumented-tests.bat [class]` | Builds app + test APKs, installs both, and runs `AndroidJUnitRunner` on the device. Optional class filter. |
| `push-fixtures.bat` | Generates `sample.pdf`, `notes.txt`, `README.nfo`, `book.text`, `alice.epub`, `sample.cbz`, the encrypted PDF, and downloads a few public-domain Project Gutenberg books (EPUB/TXT/HTML) into `gutenberg/`. Then pushes everything to `/sdcard/Download/pdfr-fixtures/`. |
| `open-fixture.bat <name> [mime]` | Restarts PDFr, stages a generated fixture inside its cache with `run-as`, then sends the app its own FileProvider URI. Needs the debug build. Falls back to a MediaStore `ACTION_VIEW` if the app is not debuggable. |
| `manual-testing.bat` | Interactive menu for the scenarios below. |
| `gestures.ps1 <gesture>` | Raw multitouch/tap helpers: `pinch-out`, `pinch-in`, `double-tap`, `select-word`, `swipe-up`. |
| `show-cache.bat` | `run-as` listing of `cache/documents`, useful after reordering, rotating or rewriting. |
| `logcat-app.bat` | Last 400 log lines for the app PID, or recent `AndroidRuntime` crashes. |

## Manual scenarios

`manual-testing.bat` drives this checklist:

1. **Open a PDF** — `sample.pdf` shows 14 pages, the toolbar shows the file name
   and 100%.
2. **Double-tap zoom** — 200%, 400%, 600%, then back to 100%.
3. **Pinch zoom** — two-finger spread clamps at 600%, pinch together clamps at
   100%. Single-finger scrolling still works while zoomed.
4. **Text selection** — long-press a word, drag, then Copy / Select all / Close.
5. **Reorder pages** — overflow menu → Reorder pages… → `1-3,7,9-e,3` on
   `sample.pdf` produces an 11-page `sample (reordered).pdf`. `show-cache.bat`
   should show the new name; the old cache file is gone. Pages not listed are
   deleted, so the result keeps only page 1-3, 7, 9-14 and then page 3 again.
6. **Rotate 180°** — overflow → Rotate 180°… → keep `1-e` to turn every page
   upside down, or enter `2,4` to rotate only those pages.
7. **Linearize** — overflow → Linearize… → qpdf writes
   `... (linearized).pdf`. The first 8 KiB of the file should contain
   `/Linearized`; the new copy opens with the same page count.
8. **Compact** — overflow → Compact… → MuPDF writes
   `... (compact).pdf` with unused objects removed and streams, fonts and
   images compressed. This is separate from Linearize because MuPDF 1.28 cannot
   linearize.
9. **Encrypted PDF** — open `dummy-encrypted.pdf`, password `secret`. Reorder,
   rotate, linearize or compact it; the replacement opens without a password.
10. **TXT monospace** — open `notes.txt`, `README.nfo` or `book.text`, then
    overflow → Monospace: on/off. All three should reflow.
11. **EPUB reflow** — open `alice.epub`; the text reflows to the screen and zoom
    enlarges the layout.
12. **Gutenberg samples** — open a book from `gutenberg/`: EPUB, plain text and
    HTML should all render and reflow.
13. **Rotation / process death** — rotate while reading, then enable
    "Don't keep activities" in Developer options and reopen. The file, page and
    monospace state should be restored.

## Gesture notes

`gestures.ps1` sends real Linux multitouch events, because adb cannot press two
fingers through `input`. That needs `adb root` (the script runs it for you),
which is available on emulators but usually not on retail phones.

Defaults are tuned for the `pdfr_full` AVD (1080×2340, input device
`/dev/input/event2`). On another device:

```bat
powershell -ExecutionPolicy Bypass -File scripts\gestures.ps1 pinch-out -ScreenWidth 1440 -ScreenHeight 3120
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
