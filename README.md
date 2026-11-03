<h1><img width="48" src="icon.svg" /> <code>PDFr</code></h1>

## PDF reader for Android.

<hr/>

## 👀 privacy
- no network access.
- no ads.
- no analytics.
- no data-collection.

## 🖨️ share button
- use it to share it to printer, or share it with another applications (such as gmail for attaching it to your email).

## 📖 how it can open PDFs:
- `open-with` PDFr.
- `share-to` PDFr.
- use in-app open button to open a PDF file.

<hr/>

## 🔓 opening a password protected PDF (supported)
- decrypts PDF with owner password (you enter).
- opened PDF is decrypted (without password) and you can share it from directly from `PDFr` (uses original filename).
- supports both RC4, and AES-128/AES-256 encryption.
- if password is empty, it just opens it right-away.

<hr/>

## ℹ️ info button
- file name.
- file size.
- page count.
- if it is password protected.
- any PDF metadata - title, author, subject, keywords, creator, producer, created, modified.

<hr/>

<hr/>


<hr/>

### 🤏 minimal interface
- limited/no dark theme.
- only left-to-right interface (no `RTL`).

<hr/>

## 🚧 architecture /  🪶 `APK` size
- explicitly removing `android:name="android.permission.INTERNET"` and `android:name="android.permission.ACCESS_NETWORK_STATE"`.
- rendering is based on Android's built-in `android.graphics.pdf.PdfRenderer`, via https://github.com/afreakyelf/Pdf-Viewer (`io.github.afreakyelf:Pdf-Viewer:2.4.0`), it means no text selection, no text copy (and no form filling).  
- PDFBox-Android - https://github.com/TomRoush/PdfBox-Android is bundled only to read metadata and to make protected files readable; it is never used to render a page (although it can and probably will do a better job).
- APK is about `~3MB` for release.
- Pdf-Viewer is about `100KB` most of the rest is Compose, Material 3 and the AndroidX graph.
- PDFBox would normally add several more megabytes, but BouncyCastle is excluded - so PDFBox only needs it for certificate/public-key encryption; `PDFr`'s password flow uses the standard security handler, which runs on the platform's own JCE, and PDFBox's bundled CMap/font assets are stripped out, with `androidResources.ignoreAssetsPatterns`. `PDFr` only parses dictionaries and rewrites decrypted files, so those resources are never opened.

<hr/>

### How sharing stays reliable
A PDF received from another app is often a `content://` URI that only `PDFr` can read. Rather than forwarding that URI (which may not be re-grantable), `DocumentStore` copies the bytes into `cacheDir/documents/<import-id>/` once. A staging file is renamed into place only after the copy succeeds, so a failed or self-referential import cannot truncate the open document. Both the viewer and the share sheet use that cached file, and `FileProvider` (`res/xml/file_paths.xml`) exposes only the `documents/` tree.

<hr/>

### ℹ️ Metadata and passwords
PDFBox loads the cached file with the supplied password (or an empty one). If the document is unencrypted, or its user password is empty, `PdfDocuments` reads the document information dictionary and returns the cache file unchanged.

If a real password is required, the document stays on the `Locked` state and the UI shows a password dialog. A wrong password returns to the same dialog with an error; the correct one marks the PDF as decrypted, saves a readable copy into the same private cache directory, and deletes the encrypted import. The copy is then renamed back to the original file name, so the viewer, the title and the share sheet all keep the name the user opened.

Two consequences are worth knowing:
- 🔓📄 The decrypted copy lives in `PDFr`'s private cache until the next import or normal cache eviction. It is not added to the original location.
- Sharing a protected document shares the decrypted copy, under its original file name.

Certificate/public-key encrypted PDFs are not supported (for now). Supporting them would mean re-adding BouncyCastle and roughly doubling the APK again.

<hr/>

## 🔏 Signing

Release builds read their keystore from `keystore.properties` in the project
root (git-ignored), or from environment variables for CI:

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=alias
keyPassword=…
```

| Property | Environment variable |
| --- | --- |
| `storeFile` | `PDFR_KEYSTORE_FILE` |
| `storePassword` | `PDFR_KEYSTORE_PASSWORD` |
| `keyAlias` | `PDFR_KEY_ALIAS` |
| `keyPassword` | `PDFR_KEY_PASSWORD` |

```bash
./gradlew assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

If no signing material is found, Gradle still builds, but produces an
**unsigned** APK — verify before distributing. Back up the keystore and its
password: losing either means future versions can no longer update the installed
app.

<hr/>


## 🗺️ Layout
```
app/src/main/java/com/karako/pdfr/
├── PDFrApplication.kt       # initializes PDFBox's resource loader
├── MainActivity.kt          # intent intake (VIEW / SEND / picker)
├── data/
│   ├── DocumentStore.kt     # copies the incoming URI into cache
│   ├── PdfDocuments.kt      # metadata + password decryption
│   └── ShareSheet.kt        # ACTION_SEND via FileProvider
└── ui/
    ├── ReaderViewModel.kt   # reader/locked state, import, unlock
    ├── ReaderApp.kt         # Compose UI, info and password dialogs
    └── theme/Theme.kt
```

<hr/>

### 🔨 build
- JDK 17 .
- Android SDK 35 (Android 15.0, "VanillaIceCream", https://dl.google.com/android/repository/source-35_r01.zip ) .
- gradle 8.3 (self installed) .

### 🧹 build - clean (cleanup broken gradle + cache + artifacts - Windows OS)
1. `pwsh -Command "Remove-Item -Recurse -Force '.\.gradle' -Verbose"`
2. `pwsh -Command "Remove-Item -Recurse -Force $env:USERPROFILE\.gradle -Verbose"`
3. `call ".\gradlew.bat" clean --info`

### 📦 debug build / install
- debug build - `./gradlew assembleDebug` (built in gradle task)
- install - `adb install -r app/build/outputs/apk/debug/app-debug.apk`

### 📦 release build / install
- release build - `./gradlew assembleRelease`  (built in gradle task that signs the APK when signing data is available).
- install - `adb install -r app/build/outputs/apk/release/app-release.apk`

### rename
- `local.properties.demo` -> `local.properties` with something like this:  
```txt
sdk.dir=C\:\\Users\\Elad\\AppData\\Local\\Android\\Sdk
```
- `keystore.properties.demo` -> `keystore.properties` with something like this:  
```txt
storeFile=keystore/release.jks
storePassword=abcdef123456789abcdef123456789ab
keyAlias=alias
keyPassword=abcdef123456789abcdef123456789ab
```
- note the first time you build without release keystore the keystore will be generated and there would be no signed apk, you should run the command again, I need to fix the `app/build.gradle.kts` since it first check if the file exists (which I can evaluate later).

- signed `.apk` files will be under `/app/build/outputs/apk/release/` .

<hr/>

## Verified

Smoke-tested on an Android 15 (API 35) x86_64 emulator with the minified,
signed release APK:

- importing `sample.pdf` through the system document picker,
- opening documents with Hebrew, Japanese and accented file names; the top bar
  and info dialog show the original name rather than `_` placeholders,
- scrolling (14 pages) and rotating with the reading position kept,
- showing PDF metadata (title, author, subject, keywords, creator, producer,
  created, modified) alongside file name, original size and page count,
- opening AES-128, AES-256 and RC4-128 protected PDFs with the correct
  password, rejecting a wrong password with an inline error, and opening an
  empty-password encrypted PDF without prompting,
- process death while locked (password prompt and file name restored) and while
  unlocked (decrypted document and reading position restored),
- sharing to the Print spooler, which rendered all pages from the shared
  decrypted copy,
- sharing `PDFr`'s own PDF back to `PDFr`, which keeps the open document and its
  page position instead of corrupting the cache,
- re-importing a file with the same display name, which gets a fresh cache
  directory,
- no crashes in the crash buffer.

`file://` URIs are intentionally **not** advertised: under scoped storage an app
without broad storage access cannot read another app's file path, so those
intents would always fail.

<hr/>

## Tuning (potential changes)

- Max zoom is `PdfRendererViewCompose(maxZoomScale = 3f)`; pass a different value in `ReaderApp.kt`.
- Screenshot blocking is available through the library's `pdfView_disableScreenshots` theme attribute if you ever use `PdfViewerActivity` instead of the view.
- Rendering is `PdfRenderer`-based: there is no text layer to select or search.
- `androidResources.ignoreAssetsPatterns += "!com"` strips PDFBox's font/CMap assets. Remove that line if you start using PDFBox for text extraction or rendering; rendering does not need it, but those features do.
- If certificate/public-key encrypted PDFs ever matter, remove the BouncyCastle exclusion and the matching `-dontwarn` rule, then expect a much larger APK.

<hr/>

## License

MIT for this app. Pdf-Viewer is MIT; PDFBox-Android and PDFBox are Apache-2.0.


<hr/>

https://paypal.me/31adkarak0