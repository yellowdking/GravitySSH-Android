#!/usr/bin/env python3
"""Download and verify Smiley Sans v2.0.1 TTF and SIL OFL 1.1 license."""

import hashlib
import os
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ZIP_URL = "https://github.com/atelier-anchor/smiley-sans/releases/download/v2.0.1/smiley-sans-v2.0.1.zip"
EXPECTED_ZIP_SHA256 = "299c0be6c960ae37361762eca76f7d0cd516615435bb96c0d4b98a1e70178a07"
EXPECTED_TTF_SHA256 = "b447d7e781f08bc95c4c9f23ba71ed2b8ebb639aa7184485c71c4ca5afcd25c4"
LICENSE_URL = "https://raw.githubusercontent.com/atelier-anchor/smiley-sans/v2.0.1/LICENSE"
EXPECTED_LICENSE_SHA256 = "9401f4050f1b66c26b6ccdc8b0e14a3c1cc37aac122eda84386f25854a9bec72"

ROOT = Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"
TTF_DEST = FONT_DIR / "smiley_sans.ttf"
LICENSE_DEST = ROOT / "OFL-SmileySans.txt"


class HttpsOnlyRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        resolved = urllib.parse.urljoin(req.full_url, newurl)
        require_https(resolved)
        return super().redirect_request(req, fp, code, msg, headers, resolved)


def require_https(url: str) -> None:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme.lower() != "https" or not parsed.netloc:
        raise ValueError(f"Only HTTPS URLs are permitted, got: {url!r}")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: Path) -> None:
    require_https(url)
    opener = urllib.request.build_opener(HttpsOnlyRedirectHandler)
    req = urllib.request.Request(url, headers={"User-Agent": "GravitySSH-Android/1.0"})
    with opener.open(req, timeout=60) as resp, dest.open("wb") as out:
        require_https(resp.geturl())
        while True:
            buf = resp.read(65536)
            if not buf:
                break
            out.write(buf)


def ensure_font() -> int:
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    if TTF_DEST.exists():
        existing_digest = sha256_file(TTF_DEST).lower()
        if existing_digest == EXPECTED_TTF_SHA256:
            print(f"[fetch_fonts] font already present and verified: {TTF_DEST}")
            return 0
        print(
            f"[fetch_fonts] cached font SHA-256 mismatch ({existing_digest}), re-downloading...",
            file=sys.stderr,
        )

    with tempfile.TemporaryDirectory(prefix="gravityssh-fonts-") as tmp_dir_str:
        tmp_dir = Path(tmp_dir_str)
        tmp_zip = tmp_dir / "smiley-sans-v2.0.1.zip"
        tmp_ttf = tmp_dir / "smiley_sans.ttf"
        print(f"[fetch_fonts] downloading {ZIP_URL} ...")
        download(ZIP_URL, tmp_zip)
        digest = sha256_file(tmp_zip)
        if digest.lower() != EXPECTED_ZIP_SHA256:
            print(f"[fetch_fonts] zip SHA-256 mismatch: {digest}", file=sys.stderr)
            return 1

        ttf_written = False
        with zipfile.ZipFile(tmp_zip, "r") as zf:
            for name in zf.namelist():
                if os.path.basename(name) == "SmileySans-Oblique.ttf":
                    tmp_ttf.write_bytes(zf.read(name))
                    ttf_written = True
                    break
        if not ttf_written:
            print("[fetch_fonts] SmileySans-Oblique.ttf missing from zip", file=sys.stderr)
            return 1

        ttf_bytes = tmp_ttf.read_bytes()
        ttf_digest = hashlib.sha256(ttf_bytes).hexdigest().lower()
        if ttf_digest != EXPECTED_TTF_SHA256:
            print(f"[fetch_fonts] extracted TTF SHA-256 mismatch: {ttf_digest}", file=sys.stderr)
            return 1

        # Write verified bytes directly to TTF_DEST without staging non-font files inside res/font/
        TTF_DEST.write_bytes(ttf_bytes)
        print(f"[fetch_fonts] extracted and verified {TTF_DEST} ({TTF_DEST.stat().st_size} bytes)")
        return 0


def ensure_license() -> int:
    if LICENSE_DEST.exists() and sha256_file(LICENSE_DEST).lower() == EXPECTED_LICENSE_SHA256:
        print(f"[fetch_fonts] license already present: {LICENSE_DEST}")
        return 0
    with tempfile.TemporaryDirectory(prefix="gravityssh-license-") as tmp_dir_str:
        tmp_lic = Path(tmp_dir_str) / "OFL-SmileySans.txt"
        download(LICENSE_URL, tmp_lic)
        lic_bytes = tmp_lic.read_bytes()
        digest = hashlib.sha256(lic_bytes).hexdigest().lower()
        if digest != EXPECTED_LICENSE_SHA256:
            print(f"[fetch_fonts] license SHA-256 mismatch: {digest}", file=sys.stderr)
            return 1
        LICENSE_DEST.write_bytes(lic_bytes)
        print(f"[fetch_fonts] verified {LICENSE_DEST} ({LICENSE_DEST.stat().st_size} bytes)")
        return 0


def main() -> int:
    rc = ensure_font()
    if rc != 0:
        return rc
    return ensure_license()


if __name__ == "__main__":
    sys.exit(main())
