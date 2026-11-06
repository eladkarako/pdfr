#!/usr/bin/env python3
"""Download small public-domain books from Project Gutenberg.

These fixtures exercise the non-PDF formats MuPDF understands (EPUB, HTML and
plain text). Gutenberg books are in the public domain in the United States;
the download page and the book itself carry the exact notice. This script
stores the source URL next to every file so a reviewer can check it.

Only the Python standard library is used.

    python tools/fetch_gutenberg.py --out build/test-fixtures/gutenberg
"""

from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Book:
    slug: str
    ebook_id: int
    title: str
    author: str
    url: str
    filename: str
    kind: str


# Direct cache URLs are stable and avoid an extra redirect. Sizes are small
# enough to keep the generated fixture folder practical.
BOOKS: tuple[Book, ...] = (
    Book(
        slug="alice",
        ebook_id=11,
        title="Alice's Adventures in Wonderland",
        author="Lewis Carroll",
        url="https://www.gutenberg.org/cache/epub/11/pg11.epub",
        filename="alice-gutenberg.epub",
        kind="EPUB",
    ),
    Book(
        slug="pride",
        ebook_id=1342,
        title="Pride and Prejudice",
        author="Jane Austen",
        url="https://www.gutenberg.org/cache/epub/1342/pg1342-images.epub",
        filename="pride-and-prejudice.epub",
        kind="EPUB (with images)",
    ),
    Book(
        slug="sherlock",
        ebook_id=1661,
        title="The Adventures of Sherlock Holmes",
        author="Arthur Conan Doyle",
        url="https://www.gutenberg.org/cache/epub/1661/pg1661.txt",
        filename="sherlock-holmes.txt",
        kind="plain text",
    ),
    Book(
        slug="yellow",
        ebook_id=1952,
        title="The Yellow Wallpaper",
        author="Charlotte Perkins Gilman",
        url="https://www.gutenberg.org/cache/epub/1952/pg1952-images.html",
        filename="yellow-wallpaper.html",
        kind="HTML",
    ),
)

USER_AGENT = "PDFr-test-fixtures/1.0 (https://github.com/eladkarako/pdfr)"


def print_manifest() -> None:
    print("Project Gutenberg fixtures:")
    for book in BOOKS:
        print(
            f"  {book.filename:32s} {book.kind:18s} "
            f"#{book.ebook_id} {book.title} - {book.author}"
        )
        print(f"  {'':32s} {book.url}")


def download(book: Book, destination: Path, timeout: float) -> bool:
    request = urllib.request.Request(
        book.url,
        headers={"User-Agent": USER_AGENT, "Accept": "*/*"},
    )
    target = destination / book.filename
    partial = destination / f"{book.filename}.part"
    partial.unlink(missing_ok=True)

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status != 200:
                raise RuntimeError(f"HTTP {response.status}")
            with partial.open("wb") as handle:
                while True:
                    chunk = response.read(256 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)

        if partial.stat().st_size < 1024:
            raise RuntimeError("download was unexpectedly small")

        # A Gutenberg EPUB is a ZIP; make sure the server returned the book
        # rather than an HTML error page.
        if book.filename.endswith(".epub"):
            with partial.open("rb") as handle:
                if handle.read(2) != b"PK":
                    raise RuntimeError("not a ZIP/EPUB file")

        partial.replace(target)
        print(f"[PDFr] {book.filename:32s} {target.stat().st_size:>9,d} bytes")
        return True
    except (urllib.error.URLError, OSError, RuntimeError) as error:
        partial.unlink(missing_ok=True)
        print(
            f"[PDFr] Skipped {book.filename}: {error}",
            file=sys.stderr,
        )
        return False


def write_sources(destination: Path, downloaded: list[Book]) -> None:
    lines = [
        "Project Gutenberg test fixtures",
        "===============================",
        "",
        "These files are public-domain works downloaded from Project Gutenberg.",
        "They are used only to exercise PDFr's EPUB, HTML and plain-text reader.",
        "",
    ]
    for book in downloaded:
        lines.extend(
            [
                f"{book.filename}",
                f"  Title:  {book.title}",
                f"  Author: {book.author}",
                f"  Format: {book.kind}",
                f"  Source: {book.url}",
                f"  eBook:  https://www.gutenberg.org/ebooks/{book.ebook_id}",
                "",
            ]
        )
    lines.extend(
        [
            "Project Gutenberg: https://www.gutenberg.org/",
            "Permission is granted to copy, distribute and modify these works",
            "under the Project Gutenberg License included with each eBook.",
            "",
        ]
    )
    (destination / "SOURCES.txt").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=repo / "build" / "test-fixtures" / "gutenberg",
        help="output directory (default: <repo>/build/test-fixtures/gutenberg)",
    )
    parser.add_argument(
        "--only",
        action="append",
        default=[],
        metavar="SLUG",
        help="download only this book slug (repeatable): alice, pride, sherlock, yellow",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=60.0,
        help="network timeout in seconds (default: 60)",
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="print the catalogue without downloading anything",
    )
    args = parser.parse_args()

    if args.list:
        print_manifest()
        return 0

    selected = BOOKS
    if args.only:
        wanted = {slug.lower() for slug in args.only}
        selected = tuple(book for book in BOOKS if book.slug in wanted)
        if not selected:
            print(
                "No matching book. Use --list to see the available slugs.",
                file=sys.stderr,
            )
            return 2

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    downloaded: list[Book] = []
    for book in selected:
        if download(book, out, args.timeout):
            downloaded.append(book)

    if downloaded:
        write_sources(out, downloaded)
        print(f"[PDFr] Gutenberg fixtures written to {out}")
        return 0

    print(
        "[PDFr] No Gutenberg fixtures were downloaded (offline or blocked).",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
