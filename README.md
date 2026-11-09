<h1>📄 <code>PDFr</code> <sub><code>com.karako.pdfr</code></sub></h1>

📱 PDF reader for Android.

## 👀 Privacy

- 🚫 no network permission.
- 🛑 no ads.
- 📉 no analytics.
- 🔒 no data collection.
- 🗂️ documents are copied into the app's private cache only while they are open.

<details><summary><h2>📚 Formats supported</h2></summary>

| Format | Extension(s) | Definition | Key traits |
|--------|--------------|------------|------------|
| 📕 PDF (Portable Document Format) | `.pdf` | A fixed-layout format, created by Adobe, that preserves the exact look of a page, including fonts, images, and layout, on any device. | Best for printing and scanned or designed documents. It reflows poorly on small e-reader screens, though newer readers handle it better. |
| 📘 EPUB | `.epub` | An open, reflowable ebook standard maintained by the IDPF. It is a ZIP container holding HTML, CSS, images, and metadata. | The most widely supported ebook format, and the one most publishers use. Text adapts to the screen, and readers can change fonts and sizes. |
| 📄 XPS / OXPS | `.xps`, `.oxps` | XML Paper Specification, a fixed-layout page format from Microsoft. It is a ZIP package of XML descriptions of each page. OpenXPS is the open version of the same idea. | Designed as a print and document-exchange format, similar in purpose to PDF. It is rarely used for books today. |
| 🦸 CBZ / CBT (comic book archive) | `.cbz`, `.cbt` | A comic archive: a ZIP (CBZ) or TAR (CBT) file containing the pages as image files (usually JPEG or PNG), named in reading order. | There is no text layer or reflow. Comic readers display the images in sequence, so the format is simple and easy to create. |
| 📖 FB2 (FictionBook) | `.fb2` | An XML-based ebook format, FictionBook 2.0, that describes a book's structure and metadata, such as title, author, and chapters, rather than its appearance. | Easy to process and index for collection management. It is common in some non-English markets, especially Russian-speaking ones, but has little support in English. |
| 📚 MOBI / PalmDoc | `.mobi`, `.prc`, `.pdb` | Older Amazon and Palm ebook containers, including the Mobipocket format that predates KF8 and AZW3. | Readable but reflowed without DRM support. Most modern Kindle books use AZW3/KFX, which MuPDF does not open. |
| 📝 Markdown | `.md`, `.markdown`, `.mdown`, `.mkd`, `.mkdn` | Plain text with lightweight heading, list, link and code syntax. | Reflows to the screen width. MuPDF converts it to HTML before layout; longer suffixes are handled through a temporary `.md` copy. |
| 🖼️ Images and PNM/PAM | `.png`, `.jpg`, `.jpeg`, `.gif`, `.bmp`, `.tif`, `.tiff`, `.jp2`, `.jpx`, `.jxr`, `.wdp`, `.hdp`, `.psd`, `.pnm`, `.pbm`, `.pgm`, `.ppm`, `.pam`, `.pfm`, `.jb2` | Single-page raster or vector images. PNM/PAM is a family of simple, uncompressed image formats. | Each image opens as a one-page document. SVG is handled as a vector page and stays sharp when zoomed. |
| 🌐 HTML, XHTML and SVG | `.html`, `.htm`, `.xhtml`, `.svg` | HTML is the web's markup language for text, links, and styling. SVG (Scalable Vector Graphics) is an XML format for vector images that scale without losing quality. | Standalone HTML is rarely used as an ebook because it splits across many files. HTML and SVG are mostly used inside EPUB files, where SVG can supply scalable illustrations or fixed-layout pages. |
| 📝 Plain text | `.txt`, `.text`, `.nfo`, `.log` | A file containing only characters, with no fonts, images, colours, or styling. .nfo files are text files, often with ASCII art, traditionally used to describe software or media releases. | The most portable format, readable on almost any device. The original ebooks, such as Project Gutenberg texts, were plain text. Line endings and character encoding (usually UTF-8) can cause display problems. |
| 🧾 Source, config and data | `.cfg`, `.ini`, `.conf`, `.properties`, `.yaml`, `.yml`, `.toml`, `.json`, `.jsonl`, `.csv`, `.tsv`, `.xml`, `.c`, `.h`, `.cpp`, `.hpp`, `.cs`, `.java`, `.kt`, `.js`, `.ts`, `.py`, `.rb`, `.go`, `.rs`, `.php`, `.sh`, `.bat`, `.ps1`, `.sql`, `.css` and similar | Text files written for a program rather than for a reader: configuration, structured data and source code. | Shown as reflowable plain text with the monospace toggle. MuPDF has no handler for these suffixes, so PDFr opens a temporary `.txt` copy; the copy is byte-for-byte identical and no byte-order mark is added. MuPDF's content detector still treats a file whose first line starts with `# ` as Markdown, so such a comment may render as a heading without the marker. A file with an entirely unknown suffix is also detected from its bytes and opened as text when MuPDF cannot handle it. |

💡 note: Fixed-layout documents are rasterised page by page. Reflowable documents
(EPUB, MOBI, FB2, Markdown, HTML and text) are laid out to the current screen
width. Plain text gets its own Compose view with line numbers, wrapping, RTL
alignment and a continuous or paged layout, so it can select across paragraphs
with the native text toolbar. Office files (DOCX/XLSX/PPTX/HWPX) are not
supported.

📄 PDFr advertises the `text/*` family plus the common application text types
(JSON, XML, YAML, TOML, JavaScript, shell, SQL and others) for **Open with** and
**Share**, so configuration and source files do not need a PDFr-specific entry
for each extension. The in-app picker also offers `text/*` and
`application/octet-stream`; text detection fills in providers that report no
useful MIME type.

</details>

<hr/>

<details><summary><h2>📥 How opening works</h2></summary>

📨 A document received from another app is usually a `content://` URI that only
PDFr can read. `DocumentStore` copies the bytes once into
`cacheDir/documents/<import-id>/`, using a staging file renamed into place only
after the copy succeeds. Everything else - the reader, the info dialog and the
share sheet - uses that cached file. `FileProvider` exposes only the
`documents/` tree.

🔄 Reordering, rotating, linearizing or compacting writes a new PDF into a fresh
subdirectory of the same cache, opens it, and then deletes the previous copy.
There is deliberately no undo stack and no original PDF kept around: use the
**Open** toolbar button (or the system picker) to go back to the source file.
⚠️ Every rewrite saves without encryption, so a rewritten encrypted PDF opens
without a password.

💾 **Save as** copies the document that is currently open to a location you
choose, using the same format and filename. The cached bytes are copied
verbatim, so rewrites, embedded metadata and the original date/time stay
intact wherever the target provider allows its timestamp to be updated.

</details>

<hr/>

<details><summary><h2>✨ Features</h2></summary>

- 📂 **Open with / Share to PDFr**, or pick a file from the **Open** toolbar button.
- 💾 **Save as** next to **Open**: choose a destination and keep the current
  format, filename, extension and, where the provider supports it, the original
  modification time.
- ✂️ **Text selection and copying** - long-press a PDF page to open the page
  menu, then choose **Select all** to highlight every text block on that page
  and expose **Copy**. Plain text selects natively across paragraphs with the
  system toolbar; line numbers are never included. There are no drag handles:
  PDF selection is intentionally page-wide for now.
- 🔍 **Zoom** from 100% to 600% for fixed-layout documents with a two-finger
  pinch (any intermediate value) or a smooth double-tap glide (2x, 4x, 6x, back
  to 1x). The content resizes and pans under the fingers around the midpoint
  between them; the sharper bitmap arrives when the gesture ends. Reflowable
  documents disable pinch and double-tap: use **Text size** instead.
- 🔠 **Text size** from 5 to 40 pt (12 pt by default) for reflowable documents,
  from the overflow menu. It persists across files, rotation and process death
  and does not change the zoom of a PDF or image.
- 📑 **Plain-text layout options** - one continuous page (default on), line wrap
  (default on; horizontal scrolling when off), right-to-left direction and
  alignment (default off), line numbers on the left, or on the right in RTL
  (default on), and a monospace font override.
- 📍 **Page indicator** fades away while you read and wakes on any scroll, pan or
  zoom. The overflow menu, info editor and other dialogs scroll when needed and
  never cover more than three quarters of the screen.
- 🔀 **Reorder pages** (PDF only): enter `1-3,7,9-e` to list the pages to keep, in
  the order you want them. Pages you leave out are deleted; `e` is the last
  page, spaces are ignored, and repeats are kept. PDFr writes a new,
  unencrypted PDF into its cache and opens it in place of the current document.
- 🔄 **Rotate 180°** (PDF only): turns the listed pages upside down. The range
  defaults to `1-e`, and only the pages you list are rotated.
- ⚡ **Linearize** (PDF only): calls the bundled [qpdf](https://qpdf.readthedocs.io/)
  native library to write a genuinely linearized PDF, so the first page can be
  shown before the rest of the file is read. The new copy replaces the current
  document.
- 🗜️ **Compact** (PDF only): MuPDF's rewrite with unused objects removed and
  streams, fonts and images compressed. MuPDF 1.28 cannot linearize, so this
  stays a separate, clearly named action.
- 🔤 **Monospace** override for plain-text documents, from the overflow menu.
- 🔐 **Password-protected documents** are unlocked in memory by MuPDF. No
  decrypted copy is written when merely reading; any rewrite saves its
  replacement without encryption.
- ℹ️ **Metadata editor**: the circled **i** opens the filename, extension and the
  title/author/subject/keywords/creator/producer/date fields the document
  already has. Dates are shown as `yyyy-MM-ddThh:mm:ss` and can be typed or
  picked. Edits stay in a draft while the dialog is dismissed; **Apply** writes
  them into the PDF (or a sidecar for other formats) and reloads. Clearing a
  field removes its value.
- 📤 **Share** and 💾 **Save as** use the edited display name and timestamp, and
  PDF metadata changes are part of the bytes they copy.
- 📍 **Reading position** is preserved across rotation, reflow and process death.

</details>

<hr/>

<details><summary><h2>🏗️ Architecture</h2></summary>

```
app/src/main/java/com/karako/pdfr/
├── MainActivity.kt          # 🚪 intent intake (VIEW / SEND / picker)
├── data/
│   ├── DocumentFormats.kt   # 🏷️ MIME types, extensions and plain-text names
│   ├── DocumentInfo.kt      # 🧾 generic metadata model
│   ├── DocumentMetadata.kt  # ✏️ editable draft, PDF keys and sidecar overlay
│   ├── DocumentDates.kt     # 🕒 ISO/PDF date parsing and formatting
│   ├── MetadataSidecar.kt   # 🗂️ metadata for EPUB/image/text cache files
│   ├── PlainText.kt         # 📃 BOM-aware text decoding and line splitting
│   ├── DocumentStore.kt     # 💾 copies an incoming URI into cache
│   ├── PageRanges.kt        # 🔢 parses "1-3,7,9-e" page selections
│   ├── MuPdf.kt             # 🧵 one dedicated MuPDF thread
│   ├── MuPdfDocument.kt     # 📂 open/auth/layout/render/select/rewrite
│   ├── Qpdf.kt              # 🧵 one dedicated qpdf thread + JNI bridge
│   └── ShareSheet.kt        # 📤 ACTION_SEND via FileProvider
├── ui/
│   ├── ReaderApp.kt         # 🖥️ Compose continuous-scroll reader + dialogs
│   ├── ReaderViewModel.kt   # 🧠 reader state, viewport, selection, reflow
│   ├── PlainTextReader.kt   # 📃 continuous/paged text view with selection
│   ├── DocumentInfoDialog.kt# ✏️ editable metadata dialog
│   ├── TextSizeDialog.kt    # 🔠 reflowable text-size picker
│   └── theme/Theme.kt       # 🎨 theme
app/src/main/cpp/            # 🔌 JNI bridge to static libqpdf
app/src/main/jniLibs/<abi>/  # 📦 stripped libqpdfjni.so per ABI
app/src/test/                # 🧪 JVM tests (no device, no native code)
app/src/androidTest/         # 🤖 MuPDF/qpdf integration tests + PDF writer
tools/make_test_files.js     # 🛠️ generates manual-test documents + Gutenberg books
tools/fetch_gutenberg.py     # 🐍 standalone Project Gutenberg downloader
tools/                       # 🪟 Windows helpers: AVD, emulator, install, tests, gestures
```

🧵 MuPDF's native objects are not concurrency-safe, so every MuPDF call runs on a
single background thread (`MuPdf.dispatcher`). Pages are rendered into
`ARGB_8888` bitmaps held in a byte-sized LRU cache; structured text is cached
separately for selection.

🔗 qpdf is independent of MuPDF and can be slow on large files, so it has its own
serialized thread (`Qpdf.dispatcher`). It is built as a static library for each
Android ABI and exposed through a tiny JNI function
(`app/src/main/cpp/qpdf_jni.cpp`). The stripped `libqpdfjni.so` files are
checked into `app/src/main/jniLibs/`, so a normal Gradle build never needs the
qpdf or libjpeg sources.

</details>

<hr/>

<details><summary><h2>📦 Build</h2></summary>

- ☕ JDK 17
- 🤖 Android SDK 36
- 🐘 Gradle 9.8.2
- 🧩 The official prebuilt MuPDF AAR `com.artifex.mupdf:fitz:1.28.5` is resolved
  from `https://maven.ghostscript.com`.
- 🔧 qpdf 12.4.2 is linked statically; the stripped JNI libraries are committed,
  so the Android NDK is only needed when rebuilding them.

```bash
./gradlew assembleDebug
adb install -r build/app/outputs/apk/debug/app-universal-debug.apk
```

📁 Gradle writes this module's output to `build/app` (not `app/build`), so the
source tree stays free of generated files. Release APKs and AABs are also
copied to `build/release` after `assembleRelease` or `bundleRelease`.

📱 The build produces one APK per ABI plus a universal APK; use the matching
split for smaller installs (for example `app-x86_64-debug.apk` on an x86_64
emulator).

</details>

<hr/>

<details><summary><h2>🛠️ Rebuilding the qpdf bridge</h2></summary>

`build/qpdf/` holds the downloaded qpdf and libjpeg-turbo sources and the
per-ABI build trees. `tools/build-qpdf-android.ps1` configures and builds all
four ABIs with the Android NDK, strips the resulting `libqpdfjni.so`, and copies
it into `app/src/main/jniLibs/<abi>/`. Only do this when `qpdf_jni.cpp` or the
qpdf version changes; a normal clone builds from the checked-in libraries.

</details>

<hr/>

<details><summary><h2>🧪 Testing</h2></summary>

Three layers, all runnable from the repository root:

| Layer | Command | Needs a device? |
| --- | --- | --- |
| 🧪 JVM unit tests | `tools\run-unit-tests.bat` (or `gradlew :app:testDebugUnitTest`) | ❌ no |
| 🤖 MuPDF integration tests | `tools\run-instrumented-tests.bat` | ✅ yes |
| 📋 Manual checklist | `tools\manual-testing.bat` | ✅ yes |

- 📄 `app/src/test/README.md` - `PageRanges`, `DocumentFormats`, `DocumentInfo`,
  dates, plain-text decoding and metadata sidecars.
- 📄 `app/src/androidTest/README.md` - opens, renders, selects, reorders,
  rotates, compacts, linearizes through qpdf, unlocks AES-256 PDFs, rewrites
  metadata and toggles plain-text monospace on real MuPDF.
- 📄 `tools/README.md` - AVD creation, emulator/install helpers, fixture pushing,
  the manual checklist, the raw-multitouch pinch helper, and the
  dependency-free fixture generator/Project Gutenberg downloader.

🚀 Typical first run:

```bat
tools\create-test-avd.bat
tools\start-emulator.bat
tools\install-debug.bat
tools\push-fixtures.bat
tools\run-instrumented-tests.bat
tools\manual-testing.bat
```

🧾 The instrumented tests generate their own PDFs at runtime (including a
password-protected one), so no test binaries are checked in. For manual
testing, `node tools\make_test_files.js` creates a 14-page `sample.pdf`, text
files (`.txt`, `.nfo`, `.text`), an EPUB, a CBZ, an AES-256 PDF whose password
is `secret`, and downloads a few public-domain Project Gutenberg books
(EPUB/TXT/HTML) into `gutenberg/`.

🔏 Release builds are minified and signed from `keystore.properties` in the project
root (git-ignored), or from environment variables for CI:

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=alias
keyPassword=…
```

| Property | Environment variable |
| --- | --- |
| `storeFile` | `PDFR_STORE_FILE` |
| `storePassword` | `PDFR_STORE_PASSWORD` |
| `keyAlias` | `PDFR_KEY_ALIAS` |
| `keyPassword` | `PDFR_KEY_PASSWORD` |

⚠️ Without signing material Gradle still builds, but the release APK is signed
with the debug key. Back up the real keystore and its password: losing either
means future versions can no longer update an installed app.

</details>

<hr/>

<details><summary><h2>📏 Native size</h2></summary>
MuPDF is a real document engine, so the native library is the largest part of
the APK: roughly 11 MB for arm64-v8a, 8 MB for armeabi-v7a and 11 MB each for
the x86 ABIs. The qpdf JNI bridge adds about 3.1 MB per ABI after stripping.
The app ships ABI splits plus a universal APK, so users only need to install the
APK for their device.
</details>

<hr/>

<details><summary><h2>🕵️ Checking That an App Has No Internet Access</h2></summary>

this app is provided with this file `/app/src/main/res/xml/network_security_config.xml`  

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

meaning you can wire-up a packet inspection application,  
to make sure there are no requests generated from the app,  
this includes opening up HTML files! (but I should test it).

this most likely be moved to debug builds, but for now I'll keep in in the release too.

<hr/>

## 🎯 What this guide is about

This guide shows you how to check whether an Android app sends any data over the internet. It uses **Burp Suite**, a free tool that acts as a proxy. The proxy sits between your device and the internet, so any network request the app makes passes through it and shows up in Burp's logs. You can use this to confirm an app is offline, or to see exactly what it tries to send.

⚠️ Burp Suite can only see traffic that goes through the proxy. Some apps ignore the device's proxy settings, so a clean Burp log is good evidence but not proof on its own. The final section covers a firewall step that closes that gap.

## 🧰 What you need

- 💻 A computer on the same Wi-Fi network as your Android device (or an Android emulator)
- 🐞 **Burp Suite Community Edition** (free), from the PortSwigger website
- 📱 The Android app you want to test (an APK or installed app)
- ☕ Java, if your version of Burp requires it

## 🟢 Step 1: Start Burp Suite

1. Open Burp Suite and create a temporary project.
2. Go to **Proxy > Proxy settings** (or **Proxy > Options**).
3. Confirm there is a listener on **127.0.0.1:8080** and that it is running.
4. Turn **Intercept** off so traffic is logged without pausing requests.

## 🌐 Step 2: Find your computer's IP address

- 🪟 **Windows:** run `ipconfig` in Command Prompt and note the IPv4 address under your Wi-Fi adapter.
- 🍎 **macOS:** open **System Settings > Wi-Fi > Details** and note the IP address.
- 🐧 **Linux:** run `ip addr` and note the address on your Wi-Fi interface.

## 📲 Step 3: Point the Android device at Burp

1. On the Android device, open **Settings > Network & internet > Internet** (the name varies by manufacturer).
2. Long-press your Wi-Fi network and choose **Modify network**.
3. Under **Proxy**, select **Manual**.
4. Enter your computer's IP address as the proxy hostname and **8080** as the port.
5. Save.

If you use an emulator, set the proxy in the emulator's Wi-Fi settings to your computer's IP address and port 8080.

## 🔐 Step 4: Install the Burp certificate (for HTTPS)

Without this step, Burp can see that HTTPS connections were attempted but cannot read their contents.

1. On the Android device, open a browser and go to **http://burp** (this only works while the proxy is set).
2. Download the **CA Certificate**.
3. Go to **Settings > Security > Encryption & credentials > Install a certificate > CA certificate** and install the file.

⚠️ Apps built for Android 7.0 and later usually ignore user-installed certificates unless their network security config allows them. If an app refuses the Burp certificate, Burp will log a failed TLS handshake for that connection. That failure is still useful evidence of an attempted connection.

## 🔎 Step 5: Run the app and check the logs

1. Open the app and use every feature you want to test, including login, settings, and any screens that load content.
2. In Burp, check these tabs:
   - 📜 **Proxy > HTTP history** lists every plain HTTP and decrypted HTTPS request.
   - 🔌 **Proxy > WebSockets history** lists any WebSocket traffic.
   - 🧾 **Dashboard > Event log** shows connection errors, including failed TLS handshakes.
3. 📝 Record any hostnames or IP addresses the app contacts.

**📊 Interpreting the results:**

- ✅ **No entries at all:** the app made no requests through the proxy. Continue to the firewall step to confirm.
- 🚨 **Entries present:** the app is connecting to the internet. Note the destinations and the data sent.
- ❌ **Only failed TLS entries:** the app attempted HTTPS connections that were rejected, which still means it tried to connect.

## 🛡️ Step 6: Confirm with a firewall (recommended)

Some apps ignore the system proxy or use direct sockets, so they would not appear in Burp. To block them outright:

1. 🧱 Install a per-app firewall. On Android, **NetGuard** works without root and uses a local VPN to block internet access per app.
2. 🚫 Block the target app's internet access (Wi-Fi and mobile data).
3. 🔁 Use the app again and check whether it still loads content or reports errors.
4. 📋 Watch the firewall's connection log, which records blocked attempts even when Burp shows nothing.

If the app works fully offline, it does not need the internet. If it fails to load anything or shows errors, it depends on the network.

## ⚠️ Limitations

- 🔍 Burp only sees traffic routed through the proxy.
- 📌 Apps with certificate pinning may refuse Burp's certificate, so their HTTPS content stays hidden.
- 🧭 A clean Burp log does not rule out network use by other means, such as DNS lookups or direct sockets. Use the firewall step to check.
- ✅ Use this only on apps and devices you own or have permission to test.

## 🧹 After testing

1. 📴 Remove the proxy from the Wi-Fi network settings.
2. 🗑️ Remove the Burp CA certificate from **Settings > Security > Encryption & credentials > User credentials**.
3. ⏹️ Stop Burp Suite and delete the temporary project if you no longer need the logs.

</details>

<hr/>

<details><summary><h2>⚙️ engines</h2></summary>

- 🧩 [MuPDF](https://mupdf.com/)
- 🔧 [qPDF](https://github.com/qpdf/qpdf)

</details>

<hr/>

<details><summary><h2>⚖️ License</h2></summary>

📜 PDFr is licensed under the **GNU Affero General Public License v3.0**
(`LICENSE`). This is required because it links against MuPDF, which is also
AGPL-3.0 (or available under a commercial license from Artifex). If you
distribute a modified PDFr, the AGPL's source-offer obligations apply.

🔗 MuPDF: https://mupdf.com/ - Copyright Artifex Software, Inc.

🔗 qpdf: https://qpdf.readthedocs.io/ - Apache License 2.0, Copyright Jay
Berkenbilt and contributors. libjpeg-turbo: https://libjpeg-turbo.org/ -
IJG/BSD-style licenses. Both are linked statically into `libqpdfjni.so`.

</details>
