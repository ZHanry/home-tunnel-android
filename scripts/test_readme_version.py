"""Public entry points must describe and link the current component version."""
import json
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


if __name__ == "__main__":
    unittest.main()
