"""Identity rules for a production Android controller ABI.

Security-core libraries and the patched emulator build are rejected here.
The historical arm64 release importer does not use this module.
"""
from pathlib import Path

PAGE_SIZE = 16384
PROFILES = {
    "arm64-v8a": {"machine": 183, "elf_machine": "AArch64", "gn_cpu": "arm64"},
    "x86_64": {"machine": 62, "elf_machine": "Advanced Micro Devices X86-64", "gn_cpu": "x64"},
}


def elf_machine_and_alignment(blob):
    if len(blob) < 64 or blob[:6] != b"\x7fELF\x02\x01":
        raise SystemExit("Controller is not a 64-bit ELF library")
    machine = int.from_bytes(blob[18:20], "little")
    phoff = int.from_bytes(blob[32:40], "little")
    phentsize = int.from_bytes(blob[54:56], "little")
    phnum = int.from_bytes(blob[56:58], "little")
    if phentsize < 56 or phnum < 1 or phoff < 0 or phoff + phentsize * phnum > len(blob):
        raise SystemExit("Controller ELF is missing load-segment alignment")
    aligns = []
    for index in range(phnum):
        entry = blob[phoff + index * phentsize:phoff + (index + 1) * phentsize]
        if int.from_bytes(entry[0:4], "little") == 1:
            aligns.append(int.from_bytes(entry[48:56], "little"))
    if not aligns or any(value < PAGE_SIZE or value % PAGE_SIZE for value in aligns):
        raise SystemExit("Controller ELF page alignment is not 16 KiB")
    return machine


def require_production_manifest(manifest, abi):
    profile = PROFILES.get(abi)
    if profile is None:
        raise SystemExit("Unsupported production ABI")
    if manifest.get("test_only") is True or manifest.get("source_modified") is not False:
        raise SystemExit("Patched emulator or rewritten source cannot be a production controller")
    # Build manifests intentionally keep available=false until device acceptance.
    # Linked production_controller, exact provenance and the ELF establish that
    # this is the real engine; they do not establish runtime acceptance.
    if manifest.get("status") in {"emulator-test-only", "security-core"}:
        raise SystemExit("Security-core or emulator artifacts are not a production controller")
    if manifest.get("production_controller") is not True or manifest.get("controller_backend_linked") is not True:
        raise SystemExit("Production controller backend is not linked")
    if manifest.get("target") != abi or manifest.get("elf_machine") != profile["elf_machine"]:
        raise SystemExit("Production ABI or ELF machine does not match the selector")
    if manifest.get("android_api") != 26 or manifest.get("page_size") != PAGE_SIZE:
        raise SystemExit("Production controller must be API 26 with 16 KiB pages")
    if manifest.get("device_media_accepted") is not False:
        raise SystemExit("Device acceptance cannot be claimed by the build manifest")
    compiler = manifest.get("compiler_lock")
    if not isinstance(compiler, dict) or not compiler:
        raise SystemExit("Production compiler lock is missing")
    gn_args = manifest.get("gn_args")
    if not isinstance(gn_args, dict) or gn_args.get("target_cpu") != profile["gn_cpu"]:
        raise SystemExit("Production GN CPU does not match the selected ABI")
    if gn_args.get("is_debug") is not False:
        raise SystemExit("Production controller cannot be a debug native build")
    return profile


def require_build_policy(manifest, abi, recipe, upstream):
    """Bind every build argument/toolchain component, not just the CPU field."""
    require_production_manifest(manifest, abi)
    expected = dict(recipe["gn_args"])
    if recipe.get("schema_version", 1) >= 2:
        target = recipe.get("targets", {}).get(abi, {})
        if target.get("gn_cpu") != PROFILES[abi]["gn_cpu"]:
            raise SystemExit("Dual-ABI recipe does not list this production CPU")
        expected["target_cpu"] = target["gn_cpu"]
    elif abi != "arm64-v8a":
        raise SystemExit("Vendored recipe is arm64-only")
    if manifest.get("gn_args") != expected:
        raise SystemExit("Production GN arguments differ from the complete recipe")
    if recipe.get("schema_version", 1) >= 2:
        toolchain = upstream["toolchain"]
        compiler = {name: toolchain[name] for name in (
            "clang_revision", "clang_sub_revision", "clang_update_script_sha256",
            "chromium_tools_revision", "chromium_build_revision", "android_ndk")}
        compiler["depot_tools_revision"] = upstream["depot_tools"]["revision"]
        compiler["app_ndk_version"] = recipe["app_ndk_version"]
        if manifest.get("compiler_lock") != compiler:
            raise SystemExit("Production compiler lock differs from the source recipe")


def require_library(path, abi):
    machine = elf_machine_and_alignment(Path(path).read_bytes())
    if machine != PROFILES[abi]["machine"]:
        raise SystemExit("Production library ELF machine does not match the selected ABI")
