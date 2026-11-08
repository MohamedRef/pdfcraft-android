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


# Build Tools 36 and earlier: "Signer #1 certificate SHA-256 digest: ..."
# Build Tools 37: "V2 Signer: certificate SHA-256 digest: ..." and "V3.0 Signer: ..."
# Some releases also print a signer SDK range. Parse *only actual certificate* lines,
# never a public-key digest or an arbitrary 64-hex string elsewhere in the output.
_CERT_SHA256_LINE = re.compile(
    r"(?im)^\\s*(?:Signer\\s+#\\d+|Signer\\s+\\([^\\r\\n)]*\\)|V\\d+(?:\\.\\d+)?\\s+Signer)"
    r"\\s*:?\\s*certificate SHA-256 digest:\\s*([0-9a-fA-F:]+)\\s*$"
)


def verify(apksigner_output: str, configured_pin: str = "") -> bool:
    # apksigner's successful 'verify' exit status is checked by the invoking workflow
    # before this parser is called. Every listed signer must use the permanent owner key.
    digests = [normalize(m.group(1)) for m in _CERT_SHA256_LINE.finditer(apksigner_output)]
    if not digests or any(len(digest) != 64 or digest != EXPECTED for digest in digests):
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
