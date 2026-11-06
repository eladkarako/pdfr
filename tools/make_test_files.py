#!/usr/bin/env python3
"""Generate small documents for manual PDFr testing.

Everything here uses only the Python standard library. Password-protected
PDFs are produced with pypdf when it is available (``pip install pypdf``);
without it the other fixtures are still written.

The generated set mirrors app/src/androidTest: deterministic page labels make
it easy to see whether filtering kept the requested order and duplicates.

    python tools/make_test_files.py --out build/test-fixtures
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
import zlib
from pathlib import Path

PASSWORD = "secret"


# ---------------------------------------------------------------------------
# PDF
# ---------------------------------------------------------------------------

def _escape_pdf_text(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def _page_stream(label: str, number: int, count: int) -> bytes:
    return f"""BT
/F1 30 Tf
72 700 Td
({_escape_pdf_text(label)}) Tj
ET
BT
/F1 11 Tf
72 668 Td
(PDFr generated fixture - page {number} of {count}) Tj
ET
0.85 w
48 48 516 696 re
S
""".encode("latin-1")


def write_pdf(path: Path, labels: list[str]) -> None:
    """Write a simple, text-extractable multi-page PDF."""
    if not labels:
        raise ValueError("a fixture needs at least one page")

    out = bytearray()
    offsets: dict[int, int] = {}

    def emit(text: str) -> None:
        out.extend(text.encode("latin-1"))

    def obj(object_id: int, body: str) -> None:
        offsets[object_id] = len(out)
        emit(f"{object_id} 0 obj\n{body}\nendobj\n")

    emit("%PDF-1.4\n")
    emit("%\u00e2\u00e3\u00cf\u00d3\n")

    first_page = 4
    kids = " ".join(f"{first_page + index * 2} 0 R" for index in range(len(labels)))

    obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
    obj(2, f"<< /Type /Pages /Count {len(labels)} /Kids [{kids}] >>")
    obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")

    for index, label in enumerate(labels):
        page_id = first_page + index * 2
        content_id = page_id + 1
        stream = _page_stream(label, index + 1, len(labels))

        obj(
            page_id,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {content_id} 0 R >>",
        )

        offsets[content_id] = len(out)
        emit(f"{content_id} 0 obj\n<< /Length {len(stream)} >>\nstream\n")
        out.extend(stream)
        emit("\nendstream\nendobj\n")

    size = 4 + len(labels) * 2
    xref = len(out)

    emit(f"xref\n0 {size}\n")
    emit("0000000000 65535 f \n")
    for object_id in range(1, size):
        emit(f"{offsets[object_id]:010d} 00000 n \n")
    emit(f"trailer\n<< /Size {size} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out)


def encrypt_pdf(source: Path, destination: Path, password: str) -> bool:
    """Encrypt with pypdf when installed. Returns False when unavailable."""
    try:
        from pypdf import PdfReader, PdfWriter
    except ImportError:
        return False

    writer = PdfWriter(clone_from=str(source))
    writer.encrypt(
        user_password=password,
        owner_password=password,
        algorithm="AES-256",
    )
    with destination.open("wb") as handle:
        writer.write(handle)

    # Fail fast if the result cannot be read back with the password.
    check = PdfReader(str(destination))
    if not check.is_encrypted:
        raise RuntimeError(f"{destination} was not encrypted")
    if not check.decrypt(password):
        raise RuntimeError(f"{destination} rejected its own password")
    return True


# ---------------------------------------------------------------------------
# ZIP-based fixtures
# ---------------------------------------------------------------------------

def write_epub(path: Path, title: str, chapters: list[tuple[str, str]]) -> None:
    chapter_files = []
    manifests = []
    spine = []
    nav_items = []

    for index, (chapter_title, body) in enumerate(chapters, start=1):
        filename = f"chapter{index}.xhtml"
        chapter_files.append(
            (
                f"EPUB/{filename}",
                f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
<head><title>{chapter_title}</title></head>
<body>
<h1>{chapter_title}</h1>
{body}
</body>
</html>
""",
            )
        )
        manifests.append(
            f'<item id="chapter{index}" href="{filename}" media-type="application/xhtml+xml"/>'
        )
        spine.append(f'<itemref idref="chapter{index}"/>')
        nav_items.append(f'<li><a href="{filename}">{chapter_title}</a></li>')

    content_opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:pdfr-fixture-{abs(hash(title)) & 0xffffffff:x}</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:language>en</dc:language>
    <dc:creator>PDFr</dc:creator>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    {''.join(manifests)}
  </manifest>
  <spine>
    {''.join(spine)}
  </spine>
</package>
"""

    nav_xhtml = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Contents</title></head>
<body>
<h1>Contents</h1>
<ol>{''.join(nav_items)}</ol>
</body>
</html>
"""

    container_xml = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="EPUB/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        archive.writestr("META-INF/container.xml", container_xml)
        archive.writestr("EPUB/content.opf", content_opf)
        archive.writestr("EPUB/nav.xhtml", nav_xhtml)
        for name, content in chapter_files:
            archive.writestr(name, content)


def _png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    row = b"\x00" + bytes(rgb) * width
    raw = row * height

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def write_cbz(path: Path, pages: list[tuple[int, int, int]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for index, color in enumerate(pages, start=1):
            archive.writestr(f"page{index:02d}.png", _png(800, 1200, color))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=repo / "build" / "test-fixtures",
        help="output directory (default: <repo>/build/test-fixtures)",
    )
    parser.add_argument("--pages", type=int, default=14, help="pages in sample.pdf")
    parser.add_argument("--password", default=PASSWORD, help="password for encrypted PDFs")
    parser.add_argument("--no-encrypted", action="store_true", help="skip encrypted PDFs")
    args = parser.parse_args()

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    sample = out / "sample.pdf"
    write_pdf(sample, [f"Sample page {index}" for index in range(1, args.pages + 1)])

    dummy = out / "dummy.pdf"
    write_pdf(dummy, ["Dummy PDF file"])

    encrypted = out / "dummy-encrypted.pdf"
    encrypted_ok = False
    if not args.no_encrypted:
        encrypted_ok = encrypt_pdf(dummy, encrypted, args.password)

    notes = out / "notes.txt"
    notes.write_text(
        """PDFr plain text fixture
=======================

The quick brown fox jumps over the lazy dog.
Pack my box with five dozen liquor jugs.

0123456789 - ABCDEFGHIJKLMNOPQRSTUVWXYZ
abcdefghijklmnopqrstuvwxyz

    indented line
    another indented line

A second paragraph with enough words to wrap across a line and show
how MuPDF turns plain text into reflowable HTML pages.

<This should stay literal angle brackets>
""",
        encoding="utf-8",
    )

    write_epub(
        out / "alice.epub",
        "PDFr fixture book",
        [
            (
                "Chapter 1: Down the rabbit hole",
                "<p>"
                + " ".join(
                    ["Alice", "was", "beginning", "to", "get", "very", "tired", "of", "sitting"]
                    * 40
                )
                + "</p>",
            ),
            (
                "Chapter 2: The pool of tears",
                "<p>"
                + " ".join(
                    ["Curiouser", "and", "curiouser", "cried", "Alice", "as", "she", "grew"]
                    * 40
                )
                + "</p>",
            ),
        ],
    )

    write_cbz(
        out / "sample.cbz",
        [(220, 60, 60), (60, 140, 220), (70, 170, 90)],
    )

    print(f"[PDFr] Fixtures written to {out}")
    for fixture in sorted(out.iterdir()):
        print(f"  {fixture.name:24s} {fixture.stat().st_size:>8d} bytes")
    if not args.no_encrypted and not encrypted_ok:
        print(
            "[PDFr] pypdf is not installed, so dummy-encrypted.pdf was skipped.\n"
            "       Install it with: python -m pip install pypdf",
            file=sys.stderr,
        )
    elif encrypted_ok:
        print(f"[PDFr] dummy-encrypted.pdf password: {args.password}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
