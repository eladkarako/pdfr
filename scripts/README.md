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
| `PDFR_AVD` | `leaf_full` | AVD to start |
| `PDFR_FIXTURES` | `<repo>\build\test-fixtures` | generated fixture folder |
| `PYTHON` | `python` | Python interpreter |

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
| `push-fixtures.bat` | Generates `sample.pdf`, `notes.txt`, `alice.epub`, `sample.cbz`, the encrypted PDF, and pushes them to `/sdcard/Download/pdfr-fixtures/`. |
| `open-fixture.bat <name> [mime]` | Restarts PDFr, stages a generated fixture inside its cache with `run-as`, then sends the app its own FileProvider URI. Needs the debug build. Falls back to a MediaStore `ACTION_VIEW` if the app is not debuggable. |
| `manual-testing.bat` | Interactive menu for the scenarios below. |
| `gestures.ps1 <gesture>` | Raw multitouch/tap helpers: `pinch-out`, `pinch-in`, `double-tap`, `select-word`, `swipe-up`. |
| `show-cache.bat` | `run-as` listing of `cache/documents`, useful after filtering or optimizing. |
| `logcat-app.bat` | Last 400 log lines for the app PID, or recent `AndroidRuntime` crashes. |

## Manual scenarios

`manual-testing.bat` drives this checklist:

1. **Open a PDF** — `sample.pdf` shows 14 pages, the toolbar shows the file name
   and 100%.
2. **Double-tap zoom** — 200%, 400%, 600%, then back to 100%.
3. **Pinch zoom** — two-finger spread clamps at 600%, pinch together clamps at
   100%. Single-finger scrolling still works while zoomed.
4. **Text selection** — long-press a word, drag, then Copy / Select all / Close.
5. **Filter pages** — overflow menu → Filter pages… → `1-3,7,9-e,3` on
   `sample.pdf` produces an 11-page `sample (filtered).pdf`. `show-cache.bat`
   should show the new name; the old cache file is gone.
6. **Linearize** — overflow → Linearize… → creates a compact
   `... (optimized).pdf`. MuPDF 1.28 no longer supports true linearization; the
   dialog explains that the copy is compacted and compressed instead.
7. **Encrypted PDF** — open `dummy-encrypted.pdf`, password `secret`. Filter or
   linearize it; the replacement opens without a password.
8. **TXT monospace** — open `notes.txt`, then overflow → Monospace: on/off.
9. **EPUB reflow** — open `alice.epub`; the text reflows to the screen and zoom
   enlarges the layout.
10. **Rotation / process death** — rotate while reading, then enable
    "Don't keep activities" in Developer options and reopen. The file, page and
    monospace state should be restored.

## Gesture notes

`gestures.ps1` sends real Linux multitouch events, because adb cannot press two
fingers through `input`. That needs `adb root` (the script runs it for you),
which is available on emulators but usually not on retail phones.

Defaults are tuned for the `leaf_full` AVD (1080×2340, input device
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
