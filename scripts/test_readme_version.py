"""Public entry points must describe and link the current component version."""
import json
import hashlib
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReadmeVersionTests(unittest.TestCase):
    def test_both_languages_link_current_signed_apks_and_release_evidence(self):
        version = json.loads((ROOT / "compatibility.json").read_text())["version"]
        base = "https://github.com/ZHanry/home-tunnel-android/releases"
        for name in ("README.md", "README.en.md"):
            with self.subTest(readme=name):
                content = (ROOT / name).read_text(encoding="utf-8")
                self.assertIn(f"**{version}**", content)
                self.assertIn(f"{base}/tag/v{version}", content)
                self.assertIn("https://img.shields.io/github/v/release/ZHanry/home-tunnel-android?label=stable", content)
                for abi in ("arm64-v8a", "x86_64"):
                    self.assertIn(f"{base}/download/v{version}/HomeTunnel-Android-{version}-{abi}.apk", content)
                self.assertIn("docs/RELEASE_NOTES.md", content)

    def test_documentation_screenshots_retain_verified_original_bytes(self):
        record = json.loads((ROOT / "docs/assets/screenshots.json").read_text())
        self.assertEqual(record["component_version"], json.loads((ROOT / "compatibility.json").read_text())["version"])
        self.assertEqual(record["variant"], "debug")
        self.assertFalse(record["instrumentation"]["full_app_acceptance"])
        self.assertEqual(record["instrumentation"]["remote_media_acceptance"], "not_run")
        for screenshot in record["screenshots"]:
            with self.subTest(path=screenshot["path"]):
                image = (ROOT / screenshot["path"]).read_bytes()
                self.assertTrue(image.startswith(b"\x89PNG\r\n\x1a\n"))
                self.assertEqual(hashlib.sha256(image).hexdigest(), screenshot["sha256"])
                self.assertFalse(screenshot["edited"])


if __name__ == "__main__":
    unittest.main()
