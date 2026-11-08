"""Fast, network-free regressions for Android release authoring gates."""
import importlib.util
import pathlib
import unittest


def load(name: str, path: str):
    spec = importlib.util.spec_from_file_location(name, pathlib.Path(__file__).parent / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


notes = load("release_notes", "verify_android_release_notes.py")
signing = load("apk_signing", "verify_android_apk_certificate.py")


class ReleaseNotesTests(unittest.TestCase):
    def test_current_release_notes_are_consistent_with_manifest_version(self):
        import re
        props = pathlib.Path("apps/pdfcraft-android/android/gradle.properties").read_text()
        version = re.search(r"^pdfcraft\.version=(.+)$", props, re.MULTILINE).group(1)
        base = int(re.search(r"^pdfcraft\.versionCode=(\d+)$", props, re.MULTILINE).group(1))
        src = pathlib.Path(f"docs/releases/{version}.md").read_text()
        self.assertEqual([], notes.validate(src, version, str(base * 10 + 2)))

    def test_omitted_or_out_of_order_sections_fail(self):
        sample = "# PdfCraft Android 0.0.14-beta (14012)\n## Fixes\n- Change\n"
        self.assertTrue(notes.validate(sample, "0.0.14-beta", "14012"))
        sample2 = pathlib.Path("docs/releases/0.0.14-beta.md").read_text()
        self.assertTrue(notes.validate(sample2, "0.0.14-beta", "99999"))

    def test_generated_pr_changelog_not_inside_curated_summary(self):
        src = pathlib.Path("docs/releases/0.0.14-beta.md").read_text()
        self.assertTrue(notes.validate(src + "\n## Merged pull requests\n- PR #8\n", "0.0.14-beta", "14012"))


class SigningTests(unittest.TestCase):
    def test_signer_matches_original_owner_identity(self):
        cert = signing.EXPECTED
        output = "Signer #1 certificate SHA-256 digest: " + cert
        self.assertTrue(signing.verify(output))
        self.assertTrue(signing.verify(output, ":".join(cert[i:i + 2] for i in range(0, 64, 2))))

    def test_android37_v2_v3_signer_output_matches_original_owner(self):
        expected = signing.EXPECTED
        for prefix in ("V2 Signer:", "V3.0 Signer:", "V3.1 Signer:", "Signer #1", "Signer (minSdkVersion=24, maxSdkVersion=32)"):
            output = f"{prefix} certificate SHA-256 digest: {expected}"
            self.assertTrue(signing.verify(output), prefix)
        colon = ":".join(expected[i:i + 2] for i in range(0, 64, 2)).upper()
        self.assertTrue(signing.verify("V2 Signer: certificate SHA-256 digest: " + colon, colon))

    def test_multiple_signers_fail_closed_if_any_certificate_changes(self):
        expected = signing.EXPECTED
        good = "V2 Signer: certificate SHA-256 digest: " + expected
        matching = "\\nV3.0 Signer: certificate SHA-256 digest: " + expected
        tampered = "\\nV3.0 Signer: certificate SHA-256 digest: " + ("a" * 64)
        self.assertTrue(signing.verify(good + matching))
        self.assertFalse(signing.verify(good + tampered))
        self.assertFalse(signing.verify("V2 Signer: public key SHA-256 digest: " + expected))
        self.assertFalse(signing.verify("SourceStamp certificate SHA-256 digest: " + expected))
        self.assertFalse(signing.verify("V2 Signer: certificate SHA-256 digest: " + expected + "f"))
        self.assertFalse(signing.verify("V2 Signer: certificate SHA-256 digest: " + expected[:-1]))

    def test_wrong_missing_or_mismatched_certificate_fails(self):
        output = "Signer #1 certificate SHA-256 digest: " + signing.EXPECTED
        self.assertFalse(signing.verify(output, "a" * 64))
        self.assertFalse(signing.verify("Signer #1 certificate SHA-256 digest: " + "a" * 64))
        self.assertFalse(signing.verify("apksigner verify succeeded"))


if __name__ == "__main__":
    unittest.main()
