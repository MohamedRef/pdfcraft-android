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
        matching = "\nV3.0 Signer: certificate SHA-256 digest: " + expected
        tampered = "\nV3.0 Signer: certificate SHA-256 digest: " + ("a" * 64)
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


WORKFLOWS = pathlib.Path(".github/workflows")


def workflow_texts():
    return {path.name: path.read_text() for path in sorted(WORKFLOWS.glob("*.yml"))}


class WorkflowGateTests(unittest.TestCase):
    def test_only_the_gated_pipeline_creates_releases(self):
        # Issue #106: every route to `gh release create` must run the same gates.
        creators = {name for name, text in workflow_texts().items() if "gh release create" in text}
        self.assertEqual({"android-update.yml"}, creators)
        text = (WORKFLOWS / "android-update.yml").read_text()
        for gate in (
            "scripts/verify_android_apk_certificate.py",
            "dump badging",
            'test "$build" = "$expected_build"',
            "scripts/verify_android_release_notes.py",
            '--title "PdfCraft $version ($build)"',
            "needs: [rust-tests, android-tests]",
        ):
            self.assertIn(gate, text)

    def test_no_workflow_runs_on_pushed_tags(self):
        import re
        for name, text in workflow_texts().items():
            self.assertIsNone(re.search(r"^\s*tags:", text, re.MULTILINE), name)

    def test_one_time_release_trigger_matches_its_property_checks(self):
        # Issue #111: the commit-title trigger must name the version the property checks pin,
        # and a reused title must stop before the signing key is decoded.
        import re
        text = (WORKFLOWS / "android-update.yml").read_text()
        titles = set(re.findall(r"startsWith\(github\.event\.head_commit\.message, 'chore\(release\): publish ([^ ]+) \((\d+)\)'\)", text))
        self.assertEqual(1, len(titles), titles)
        version, build = titles.pop()
        self.assertIn(f"grep -Fxq 'pdfcraft.version={version}'", text)
        self.assertIn(f"grep -Fxq 'pdfcraft.versionCode={int(build) // 10}'", text)
        self.assertEqual(2, int(build) % 10)
        guard = text.index("Refuse to re-sign a version that is already tagged")
        self.assertLess(guard, text.index('base64 -d > "$RUNNER_TEMP/release.p12"'))

    def test_third_party_actions_are_pinned_to_commits(self):
        import re
        for name, text in workflow_texts().items():
            for action, ref in re.findall(r"uses:\s*([\w.-]+/[\w./-]+)@(\S+)", text):
                if action.startswith("actions/"):
                    continue
                self.assertRegex(ref, r"^[0-9a-f]{40}$", f"{name}: {action}@{ref}")

    def test_signing_key_is_decoded_after_the_cache_restores(self):
        # Only android-update.yml may hold the release key; decode it after the third-party cache action.
        holders = {name for name, text in workflow_texts().items() if "PDFCRAFT_KEYSTORE_BASE64" in text}
        self.assertEqual({"android-update.yml"}, holders)
        text = (WORKFLOWS / "android-update.yml").read_text()
        job = text[text.index("\n  signed-apk:"):text.index("\n  publish-release:")]
        self.assertLess(job.index("uses: Swatinem/rust-cache"), job.index("base64 -d"))


    def test_main_pushes_never_cancel_each_other(self):
        # Issue #206: a per-ref group lets a newer main push cancel the older pending run, so each
        # main commit (and manual run) needs its own group; only pull requests may cancel.
        import re
        text = (WORKFLOWS / "android-update.yml").read_text()
        top = text[text.index("\nconcurrency:"):text.index("\njobs:")]
        self.assertIn("github.sha", top)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", top)
        for job in ("signed-apk", "publish-release"):
            start = text.index(f"\n  {job}:")
            nxt = re.search(r"\n  [a-z-]+:\n", text[start + 1:])
            body = text[start:start + 1 + nxt.start()] if nxt else text[start:]
            self.assertIn("group: android-publish", body, job)
            self.assertIn("cancel-in-progress: false", body, job)
        for name, wf in workflow_texts().items():
            self.assertNotIn("cancel-in-progress: true", wf, name)

if __name__ == "__main__":
    unittest.main()
