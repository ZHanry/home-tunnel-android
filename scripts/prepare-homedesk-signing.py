"""Apply the Android-owned Gradle signing assignment to the pinned shared project."""
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--file", type=Path, default=ROOT / "homedesk-core/client/flutter/android/app/build.gradle")
args = parser.parse_args()

original = "        storeFile (System.getenv('ANDROID_RELEASE_STORE_FILE') ?: keystoreProperties['storeFile']) ? file(System.getenv('ANDROID_RELEASE_STORE_FILE') ?: keystoreProperties['storeFile']) : null"
assigned = original.replace("storeFile (", "storeFile = (", 1)
text = args.file.read_text(encoding="utf8")
if text.count(original) == 1 and assigned not in text:
    args.file.write_text(text.replace(original, assigned), encoding="utf8")
elif text.count(assigned) != 1 or original in text:
    raise SystemExit("Unexpected shared signing configuration; refusing to patch different source")
print("Explicit Gradle signing store-file assignment prepared; release identity remains required")
