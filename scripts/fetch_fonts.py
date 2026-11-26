#!/usr/bin/env python3
"""Download and verify Smiley Sans v2.0.1 TTF and SIL OFL 1.1 license."""

import hashlib
import os
import sys
import urllib.request
import zipfile
from pathlib import Path

ZIP_URL = "https://github.com/atelier-anchor/smiley-sans/releases/download/v2.0.1/smiley-sans-v2.0.1.zip"
EXPECTED_ZIP_SHA256 = "299c0be6c960ae37361762eca76f7d0cd516615435bb96c0d4b98a1e70178a07"
LICENSE_URL = "https://raw.githubusercontent.com/atelier-anchor/smiley-sans/v2.0.1/LICENSE"
EXPECTED_LICENSE_SHA256 = "9401f4050f1b66c26b6ccdc8b0e14a3c1cc37aac122eda84386f25854a9bec72"

ROOT = Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"
TTF_DEST = FONT_DIR / "smiley_sans.ttf"
LICENSE_DEST = ROOT / "OFL-SmileySans.txt"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: Path) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": "GravitySSH-Android/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp, dest.open("wb") as out:
        while True:
            buf = resp.read(65536)
            if not buf:
                break
            out.write(buf)


def ensure_font() -> int:
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    if TTF_DEST.exists() and TTF_DEST.stat().st_size > 1_000_000:
        print(f"[fetch_fonts] font already present: {TTF_DEST}")
        return 0

    tmp_zip = FONT_DIR / "smiley-sans-v2.0.1.zip"
    print(f"[fetch_fonts] downloading {ZIP_URL} ...")
    download(ZIP_URL, tmp_zip)
    digest = sha256_file(tmp_zip)
    if digest.lower() != EXPECTED_ZIP_SHA256:
        tmp_zip.unlink(missing_ok=True)
        print(f"[fetch_fonts] zip SHA-256 mismatch: {digest}", file=sys.stderr)
        return 1

    ttf_written = False
    with zipfile.ZipFile(tmp_zip, "r") as zf:
        for name in zf.namelist():
            if os.path.basename(name) == "SmileySans-Oblique.ttf":
                TTF_DEST.write_bytes(zf.read(name))
                ttf_written = True
                break
    tmp_zip.unlink(missing_ok=True)
    if not ttf_written:
        print("[fetch_fonts] SmileySans-Oblique.ttf missing from zip", file=sys.stderr)
        return 1
    print(f"[fetch_fonts] extracted {TTF_DEST} ({TTF_DEST.stat().st_size} bytes)")
    return 0


def ensure_license() -> int:
    if LICENSE_DEST.exists() and sha256_file(LICENSE_DEST).lower() == EXPECTED_LICENSE_SHA256:
        print(f"[fetch_fonts] license already present: {LICENSE_DEST}")
        return 0
    tmp_lic = ROOT / "OFL-SmileySans.txt.tmp"
    download(LICENSE_URL, tmp_lic)
    digest = sha256_file(tmp_lic)
    if digest.lower() != EXPECTED_LICENSE_SHA256:
        tmp_lic.unlink(missing_ok=True)
        print(f"[fetch_fonts] license SHA-256 mismatch: {digest}", file=sys.stderr)
        return 1
    tmp_lic.replace(LICENSE_DEST)
    print(f"[fetch_fonts] verified {LICENSE_DEST} ({LICENSE_DEST.stat().st_size} bytes)")
    return 0


def main() -> int:
    rc = ensure_font()
    if rc != 0:
        return rc
    return ensure_license()


if __name__ == "__main__":
    sys.exit(main())
