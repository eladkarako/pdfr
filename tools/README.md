# Test-fixture generator

generates few files to be used in testings.  

it writes them to `/build/test-fixtures/` (from the script's perspective that's `../build/test-fixtures/`).  

### Windows OS.

- run `make_test_files.cmd`
it will download `qpdf` and `node` if they are missing from current folder or `PATH` (uses `curl` and `7z` which it assumes exists in current folder or `PATH` which they do, in my PC).

### Other OS
- you'll need `node` and `qpdf` binaries available either in same folder or in system's `PATH`.

<hr/>

### used in testings. 

- `adb.exe` feeds those to...
- `C:\Users\Elad\AppData\Local\Android\Sdk\emulator\emulator.exe` which uses...
- `C:\Users\Elad\AppData\Local\Android\Sdk\emulator\qemu\windows-x86_64\qemu-system-x86_64.exe` with... 
- a lite VM `avd` I pre-set for the project.

<hr/>

### qPDF binaries:

- windows x64 - https://github.com/qpdf/qpdf/releases/download/v12.4.2/qpdf-12.4.2-msvc64.zip
- linux x64 - https://github.com/qpdf/qpdf/releases/download/v12.4.2/qpdf-12.4.2-bin-linux-x86_64.zip
- linux aarch64 - https://github.com/qpdf/qpdf/releases/download/v12.4.2/qpdf-12.4.2-bin-linux-aarch64.zip
- macos x64 - https://github.com/qpdf/qpdf/releases/download/v12.4.2/qpdf-12.4.2-bin-macos-x86_64.zip
- macos arm64 - https://github.com/qpdf/qpdf/releases/download/v12.4.2/qpdf-12.4.2-bin-macos-arm64.zip

<hr/>

### old python

this is based on `make_test_files.py` which used `pyqpdf` (`python -m pip install pypdf`).  
if you prefer it, use the git history to fetch it.


### encryption

The password is `secret`.

## Output

| File | Contents |
| --- | --- |
| `sample.pdf` | 14 letter-size pages labelled `Sample page 1` … `Sample page 14`. |
| `dummy.pdf` | A one-page PDF. |
| `dummy-encrypted.pdf` | A one-page PDF encrypted with AES-256 (password `secret`). |
| `notes.txt` | Plain text with long lines, indentation and literal angle brackets. |
| `alice.epub` | A minimal two-chapter EPUB 3 book. |
| `sample.cbz` | Three solid-colour PNG pages. |

The pages are real, text-extractable PDFs (base-14 Helvetica), so selection,
filtering and `pypdf` verification all work.

Useful options:

```text
--out DIR           output directory (default: <repo>\build\test-fixtures)
--pages N           number of pages in sample.pdf (default 14)
--password TEXT     password for dummy-encrypted.pdf (default secret)
--no-encrypted      do not require pypdf
```

`scripts\push-fixtures.bat` runs this generator and copies the results to
`/sdcard/Download/pdfr-fixtures/` on the connected device.
