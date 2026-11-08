#!/usr/bin/env python3
"""Pin the public production signing certificate for APKs handed across CI jobs."""
import os
from pathlib import Path
import re
import sys

# Public SHA256 fingerprint of the existing PdfCraft owner PKCS12 certificate.
EXPECTED = "0cb9f18f2e41e2cf0b0f1866b409adff798c1d83dfe6325d03fbb0b13ee051c1"


def normalize(text: str) -> str:
    return re.sub(r"[^0-9a-f]", "", text.lower())


def verify(apksigner_output: str, configured_pin: str = "") -> bool:
    found = re.search(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", apksigner_output)
    if not found or normalize(found.group(1)) != EXPECTED:
        return False
    return not configured_pin or normalize(configured_pin) == EXPECTED


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_android_apk_certificate.py apksigner-output.txt", file=sys.stderr)
        return 2
    if verify(Path(sys.argv[1]).read_text(), os.environ.get("PDFCRAFT_CERT_SHA256", "")):
        print("APK signer matches the pinned permanent owner signing certificate.")
        return 0
    print("APK signer mismatch; refusing release publication.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
