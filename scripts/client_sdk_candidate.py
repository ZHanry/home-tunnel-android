"""Verify Android SDK subjects in the original signed full-client candidate.

The returned ABI view is an in-memory projection, never a replacement signed
index. The import lock records the original client-candidate.json digest.
"""
import importlib.util
from pathlib import Path
import re

SPEC = importlib.util.spec_from_file_location("sdk_candidate_policy", Path(__file__).with_name("import-sdk-candidate.py"))
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)
INDEX = "client-candidate.json"


def verify_tree(directory, revision, run, verify_blob, identity):
    record = SDK.read_json(SDK.local_file(directory, INDEX))
    expected = {"schema_version": 1, "repository": SDK.REPOSITORY, "revision": revision,
                "verification_stage": "candidate", "source_modified": False,
                "tag_published": False, "acceptance_complete": False}
    if any(type(record.get(key)) is not type(value) or record.get(key) != value for key, value in expected.items()):
        raise SystemExit("SDKs require the original unpublished client candidate")
    SDK.verify_run(run, revision, run["id"], run["ref"], "client")
    caller, signer, _, _ = SDK.PROFILES["client"]
    build = record.get("build", {})
    expected_build = {"repository": SDK.REPOSITORY, "caller_workflow": caller, "signer_workflow": signer,
                      "run_id": str(run["id"]), "run_attempt": run["run_attempt"], "source_ref": run["ref"]}
    if (type(build.get("run_attempt")) is not int or build["run_attempt"] < 1 or
            any(build.get(key) != value for key, value in expected_build.items()) or
            not re.fullmatch(r"refs/heads/[^\s]+", run["ref"])):
        raise SystemExit("Client SDK index names a different workflow/run/attempt/ref")
    version = record.get("version", "")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-rc\.[1-9][0-9]*)?", version):
        raise SystemExit("Client candidate version is invalid")
    files = record.get("files")
    if (not isinstance(files, dict) or not 16 <= len(files) <= 254 or
            len({name.casefold() for name in files}) != len(files) or
            {INDEX, INDEX + ".sigstore.json"} & set(files)):
        raise SystemExit("Invalid original client file inventory")
    if {path.name for path in directory.iterdir()} != set(files) | {INDEX, INDEX + ".sigstore.json"}:
        raise SystemExit("Client candidate contains unexpected or missing subjects")
    for name, item in files.items():
        path = SDK.local_file(directory, name)
        if (not isinstance(item, dict) or type(item.get("bytes")) is not int or
                item["bytes"] != path.stat().st_size or item["bytes"] < 1 or SDK.digest(path) != item.get("sha256")):
            raise SystemExit("Client candidate bytes differ from the signed index")
    bundle = SDK.local_file(directory, INDEX + ".sigstore.json")
    if not 1 <= bundle.stat().st_size <= 1024 * 1024:
        raise SystemExit("Client index signature is empty or oversized")
    verify_blob(directory / INDEX, bundle, identity)
    abis, sdk_names = {}, set()
    for abi, token in (("arm64-v8a", "arm64"), ("x86_64", "x86_64")):
        archive = f"HomeTunnel-Remote-SDK-{version}-android-{token}.zip"
        suffix = "" if abi == "arm64-v8a" else "-x86_64"
        provenance_name = f"android-sdk{suffix}-provenance.json"
        sbom_name = f"android-sdk{suffix}.spdx.json"
        provenance = SDK.read_json(SDK.local_file(directory, provenance_name))
        names = (archive, archive + ".sha256", provenance_name, sbom_name)
        subjects = []
        for name in names:
            signature = name + ".sigstore.json"
            if name not in files or signature not in files:
                raise SystemExit("Client candidate omits a signed SDK subject")
            subjects.append({"name": name, **files[name], "sigstore_bundle": signature,
                             "sigstore_sha256": files[signature]["sha256"]})
            sdk_names.update((name, signature))
        item = {key: provenance.get(key) for key in (
            "archive_sha256", "archive_bytes", "source_tree_sha256", "upstream_lock_sha256", "recipe_sha256",
            "compiler_lock", "gn_args", "elf_machine", "controller_backend_linked", "production_controller", "device_media_accepted")}
        item.update(archive=archive, provenance=provenance_name, provenance_sha256=files[provenance_name]["sha256"],
                    sbom=sbom_name, sbom_sha256=files[sbom_name]["sha256"], subjects=subjects)
        abis[abi] = item
    normalized = {"source_revision": revision, "version": version, "workflow_run_id": str(run["id"]),
                  "source_tree_sha256": abis["arm64-v8a"]["source_tree_sha256"],
                  "upstream_lock_sha256": abis["arm64-v8a"]["upstream_lock_sha256"], "abis": abis}
    return SDK.verify_sdk_payload(normalized, directory, revision, verify_blob, identity,
                                  (set(files) - sdk_names) | {INDEX, INDEX + ".sigstore.json"})
