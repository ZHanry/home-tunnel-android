"""Current source docs distinguish pending universal products from published ones."""
import json
import hashlib
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReadmeVersionTests(unittest.TestCase):
    def test_both_languages_describe_current_universal_apk_and_release_scope(self):
        compatibility = json.loads((ROOT / "compatibility.json").read_text())
        version = compatibility["version"]
        base = "https://github.com/ZHanry/home-tunnel-android/releases"
        acceptance_path = ROOT / f"docs/release/acceptance-{version}.json"
        for name in ("README.md", "README.en.md"):
            with self.subTest(readme=name):
                content = (ROOT / name).read_text(encoding="utf-8")
                self.assertIn(f"**{version}**", content)
                self.assertIn(f"NestLink-Android-{version}.apk", content)
                self.assertNotIn(f"HomeTunnel-Android-{version}-", content)
                self.assertIn("docs/RELEASE_NOTES.md", content)
                self.assertIn("docs/HOMEDESK_RELEASE.md", content)
                if acceptance_path.is_file():
                    self.assertIn(f"{base}/tag/v{version}", content)
                    self.assertIn(f"{base}/download/v{version}/NestLink-Android-{version}.apk", content)
                else:
                    self.assertRegex(content, r"尚未发布|not published")
                    self.assertRegex(content, r"正在构建和验收|under implementation and acceptance")
                    self.assertIsNone(compatibility["tested_combination"])
                    self.assertNotIn(f"{base}/download/v{version}/", content)

    def test_removed_historical_screenshot_record_is_retained_without_relabeling(self):
        original = (ROOT / "docs/assets/screenshots.json").read_bytes()
        self.assertEqual(hashlib.sha256(original).hexdigest(), "5671a7eb5bd8e079ef7e564a900f9b13a2c4ad96ab78431ebb5faf0a979c6c72")
        self.assertEqual(json.loads(original), {"version": "13.0.0", "screenshots": []})

    def test_any_new_documentation_screenshots_have_current_original_byte_manifest(self):
        version = json.loads((ROOT / "compatibility.json").read_text())["version"]
        images = {path.resolve() for path in (ROOT / "docs").rglob("*") if path.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp", ".gif")}
        manifest_path = ROOT / f"docs/assets/{version}/manifest.json"
        if not images:
            self.assertFalse(manifest_path.is_file(), "An image manifest must contain actual newly captured images")
            return
        record = json.loads(manifest_path.read_text())
        self.assertEqual(record["component_version"], version)
        self.assertFalse(record["installed_acceptance_claim"])
        self.assertFalse(record["remote_session_verified"])
        self.assertTrue(record["screenshots"])
        self.assertEqual(images, {(ROOT / screenshot["path"]).resolve() for screenshot in record["screenshots"]})
        for screenshot in record["screenshots"]:
            with self.subTest(path=screenshot["path"]):
                image = (ROOT / screenshot["path"]).read_bytes()
                self.assertTrue(image.startswith(b"\x89PNG\r\n\x1a\n"))
                self.assertEqual(hashlib.sha256(image).hexdigest(), screenshot["sha256"])
                self.assertFalse(screenshot["edited"])


if __name__ == "__main__":
    unittest.main()
