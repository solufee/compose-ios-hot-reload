#!/usr/bin/env python3
"""Tests for check-klib-deps.py on small fake klibs (no Kotlin needed).

  python3 scripts/test-check-klib-deps.py      # prints one line per check, exit 0 if all pass
"""
import json
import os
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "check-klib-deps.py")


def manifest(name, deps):
    d = " ".join(x.replace(":", "\\:") for x in deps)
    return f"unique_name={name.replace(':', chr(92) + ':')}\n" + (f"depends={d}\n" if deps else "") + "short_name=x\n"


def klib_dir(root, dirname, name, deps):
    p = os.path.join(root, dirname)
    os.makedirs(os.path.join(p, "default"))
    open(os.path.join(p, "default", "manifest"), "w").write(manifest(name, deps))
    return p


def klib_file(root, filename, name, deps):
    p = os.path.join(root, filename)
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("default/manifest", manifest(name, deps))
    return p


def run(*args):
    r = subprocess.run([sys.executable, TOOL, *args], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def main():
    results = []

    def check(label, cond, out):
        results.append(cond)
        print(f"{'PASS' if cond else 'FAIL'} {label}" + ("" if cond else f"\n{out}"))

    with tempfile.TemporaryDirectory() as t:
        compose = os.path.join(t, "compose-klibs")
        ext = os.path.join(t, "ext")
        os.makedirs(compose)
        os.makedirs(ext)
        klib_dir(compose, "ui", "org.jetbrains.compose.ui:ui",
                 ["stdlib", "org.jetbrains.kotlin.native.platform.UIKit", "androidx.compose.runtime:runtime",
                  "androidx.navigationevent:navigationevent-compose"])
        klib_dir(compose, "foundation", "org.jetbrains.compose.foundation:foundation",
                 ["stdlib", "org.jetbrains.compose.ui:ui", "androidx.graphics:graphics-shapes"])
        rt = klib_file(ext, "runtime.klib", "androidx.compose.runtime:runtime", ["stdlib"])
        ne = klib_file(ext, "navigationevent-compose.klib", "androidx.navigationevent:navigationevent-compose",
                       ["stdlib", "androidx.compose.runtime:runtime"])
        gs = klib_file(ext, "graphics-shapes.klib", "androidx.graphics:graphics-shapes", ["stdlib"])

        rc, out = run(compose, ext)
        check("closed set of directory and .klib files -> exit 0", rc == 0 and "closed (5 klibs" in out, out)

        os.remove(ne)
        rc, out = run(compose, ext)
        check("missing navigationevent-compose -> exit 1, names it and who needs it",
              rc == 1 and "MISSING androidx.navigationevent:navigationevent-compose (needed by org.jetbrains.compose.ui:ui)" in out,
              out)

        spec = {"externalKlibs": [
            {"coordinate": "androidx.navigationevent:navigationevent-compose-iossimulatorarm64:1.1.1",
             "uniqueName": "androidx.navigationevent:navigationevent-compose",
             "depends": ["stdlib", "androidx.compose.runtime:runtime"]}], "bundledKlibs": []}
        sp = os.path.join(t, "external-klibs.json")
        json.dump(spec, open(sp, "w"))
        rc, out = run("--external-klibs", sp, compose, ext)
        check("the missing klib supplied by --external-klibs (uniqueName/depends) -> exit 0", rc == 0, out)

        klib_file(ext, "runtime-old.klib", "androidx.compose.runtime:runtime", ["stdlib"])
        rc, out = run("--external-klibs", sp, compose, ext)
        check("same unique_name twice -> exit 1 DUPLICATE", rc == 1 and "DUPLICATE androidx.compose.runtime:runtime" in out, out)

        del spec["externalKlibs"][0]["uniqueName"]
        json.dump(spec, open(sp, "w"))
        rc, out = run("--external-klibs", sp, compose)
        check("external entry without uniqueName/depends -> exit 2", rc == 2 and "regenerate" in out, out)

        rc, out = run(os.path.join(t, "nope"))
        check("nonexistent path -> exit 2", rc == 2, out)
        rc, out = run()
        check("no arguments -> exit 2", rc == 2, out)

        os.remove(gs)
        dist = os.path.join(t, "dist")
        klib_dir(os.path.join(dist, "klib", "common"), "stdlib", "stdlib", [])
        rc, out = run("--dist", dist, rt)
        check("--dist: stdlib from klib/common counts as present", rc == 0, out)
        rc, out = run("--dist", dist, compose, rt)
        check("--dist: a platform klib not in the dist is missing",
              rc == 1 and "MISSING org.jetbrains.kotlin.native.platform.UIKit" in out, out)

    print(f"{sum(results)}/{len(results)} passed")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
