"""Published-SDK boundary tests. No real signatures, devices or releases are simulated as accepted."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("controller_fetch", Path(__file__).with_name("fetch-remote-controller.py"))
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)


class ControllerSDKReleasePolicy(unittest.TestCase):
    def lock(self):
        return {"schema_version": 1, "repository": FETCH.REPOSITORY, "tag": "v8.0.0-rc.1", "source_revision": "a" * 40,
                "asset": "HomeTunnel-Remote-SDK-8.0.0-rc.1-android-arm64.zip", "sha256": "b" * 64,
                "provenance_sha256": "c" * 64, "controller_manifest_sha256": "d" * 64}

    def test_lock_requires_exact_tag_commit_asset_and_all_digests(self):
        lock = self.lock(); source = {"source_revision": "a" * 40, "source_tree_dirty": False}
        FETCH.validate_lock(lock, source)
        for key, value in (("repository", "other/repo"), ("tag", "main"), ("tag", "v8.0.0-rc.0"),
                           ("source_revision", "e" * 40), ("asset", "../escape.zip"), ("controller_manifest_sha256", None)):
            with self.subTest(key=key, value=value), self.assertRaises(SystemExit):
                FETCH.validate_lock(dict(lock, **{key: value}), source)
        with self.assertRaises(SystemExit):
            FETCH.validate_lock(lock, dict(source, source_tree_dirty=True))

    def test_release_ref_is_resolved_through_annotated_tags(self):
        lock = self.lock()
        def fetch(path):
            if path.startswith("releases/"):
                return {"tag_name": lock["tag"], "draft": False}
            if path.startswith("git/ref/"):
                return {"object": {"type": "tag", "sha": "f" * 40}}
            return {"object": {"type": "commit", "sha": lock["source_revision"]}}
        FETCH.verify_publication(lock, fetch)
        with self.assertRaisesRegex(SystemExit, "moved"):
            FETCH.verify_publication(dict(lock, source_revision="b" * 40), fetch)
        with self.assertRaisesRegex(SystemExit, "draft"):
            FETCH.verify_publication(lock, lambda _: {"tag_name": lock["tag"], "draft": True})

    def test_prepared_build_artifacts_can_never_satisfy_published_release_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary); lock = self.lock()
            manifest = {"repository": FETCH.REPOSITORY, "revision": lock["source_revision"], "version": lock["tag"][1:],
                        "component": "client", "rc_tag": lock["tag"], "verification_stage": "prepared"}
            (directory / "release-manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(SystemExit, "verified client release"):
                FETCH.verify_release_files(directory, lock)

    def test_sdk_extraction_rejects_escaping_and_changed_members(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary); archive = directory / "sdk.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("../escape", b"fixture")
            with self.assertRaisesRegex(SystemExit, "Unsafe"):
                FETCH.extract_sdk(archive, directory / "out", {"files": {"../escape": "a" * 64}})
            self.assertFalse((directory / "out").exists())
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("sdk/file", b"fixture")
            with self.assertRaisesRegex(SystemExit, "file differs"):
                FETCH.extract_sdk(archive, directory / "out", {"files": {"sdk/file": hashlib.sha256(b"changed").hexdigest()}})


if __name__ == "__main__":
    unittest.main()
