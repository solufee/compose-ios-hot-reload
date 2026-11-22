#!/usr/bin/env python3
"""Check that the patches in this tree are the ones a toolchain was built from.

  scripts/check-toolchain-patches.py <toolchain.json> [--pinned gradle-plugin/src/main/resources/.../pinned-toolchain.json]

Compares the sha256 of patches/kotlin-native-hot-reload.patch, patches/llvm-kaldo.patch and
patches/compose-multiplatform-core-build.patch with kotlin.patchSha256, llvm.patchSha256 and compose.patchSha256 of the
toolchain's toolchain.json (and, with --pinned, with the plugin's pinned toolchain). Prints one line per patch and
exits 1 on any mismatch.
"""
import hashlib
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PATCHES = [
    ("kotlin", "patches/kotlin-native-hot-reload.patch"),
    ("llvm", "patches/llvm-kaldo.patch"),
    ("compose", "patches/compose-multiplatform-core-build.patch"),
]


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 2
    manifests = [("toolchain.json", argv[0])]
    if "--pinned" in argv:
        manifests.append(("pinned-toolchain.json", argv[argv.index("--pinned") + 1]))
    loaded = [(label, json.load(open(path))) for label, path in manifests]
    for label, m in loaded:
        print(f"{label}: toolchainVersion {m.get('toolchainVersion')}")
    bad = 0
    for key, rel in PATCHES:
        sha = hashlib.sha256(open(os.path.join(ROOT, rel), "rb").read()).hexdigest()
        for label, m in loaded:
            want = (m.get(key) or {}).get("patchSha256")
            ok = want == sha
            bad += not ok
            print(f"{'OK  ' if ok else 'DIFF'} {rel} {sha} {label} {key}.patchSha256 {want}")
    print("all patches match" if not bad else f"{bad} mismatch(es)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
