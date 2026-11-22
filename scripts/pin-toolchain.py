#!/usr/bin/env python3
"""Pin the plugin to a toolchain: copy its version, base commits and patch sha256 into pinned-toolchain.json.

  scripts/pin-toolchain.py <toolchain.json>

Writes toolchainVersion, kotlin.{version,baseCommit,patchSha256}, compose.{version,baseCommit,patchSha256} and
llvm.{baseCommit,patchSha256} of the given toolchain.json into
gradle-plugin/src/main/resources/com/solufee/cmp/hotreload/pinned-toolchain.json (other fields unchanged) and prints
what changed. Refuses a toolchain with another interfaceVersion (that needs plugin changes, not only a pin).
Check afterwards with scripts/check-toolchain-patches.py <toolchain.json> --pinned <pinned-toolchain.json>.
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PINNED = os.path.join(ROOT, "gradle-plugin/src/main/resources/com/solufee/cmp/hotreload/pinned-toolchain.json")
FIELDS = {"kotlin": ("version", "baseCommit", "patchSha256"), "compose": ("version", "baseCommit", "patchSha256"), "llvm": ("baseCommit", "patchSha256")}


def main(argv):
    if len(argv) != 1 or argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 2
    tc = json.load(open(argv[0]))
    pin = json.load(open(PINNED))
    if tc.get("interfaceVersion") != pin.get("interfaceVersion"):
        print(f"interfaceVersion {tc.get('interfaceVersion')} != pinned {pin.get('interfaceVersion')}: not a pin-only change")
        return 1
    changes = []

    def put(obj, key, value, label):
        if obj.get(key) != value:
            changes.append(f"{label}: {obj.get(key)} -> {value}")
            obj[key] = value

    put(pin, "toolchainVersion", tc["toolchainVersion"], "toolchainVersion")
    for part, keys in FIELDS.items():
        for k in keys:
            put(pin[part], k, tc[part][k], f"{part}.{k}")
    with open(PINNED, "w") as f:
        json.dump(pin, f, indent=2)
        f.write("\n")
    print("\n".join(changes) if changes else "already pinned to this toolchain")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
