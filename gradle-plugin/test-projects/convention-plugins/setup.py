#!/usr/bin/env python3
"""#76: turn a wizard-template project (KmpHr: shared + core) into the convention-plugin project in template/.

Laid out the way many multi-module apps are: build-logic is an included build with two convention plugins
(demo.kmp.library = KMP + kotlinx-serialization + Koin compiler plugin; demo.kmp.compose = that + Compose), the
Kotlin/Compose/serialization/Koin Gradle plugins reach the build only through build-logic's dependencies, and the
modules apply nothing but a convention id (the app module also applies the CMP hot reload plugin).

Usage: setup.py PROJECT_DIR --plugin-repo URL --plugin-version V --toolchain DIR --udid U [--port N]
"""
import argparse
import os
import shutil

ap = argparse.ArgumentParser()
ap.add_argument("project")
ap.add_argument("--plugin-repo", required=True)
ap.add_argument("--plugin-version", required=True)
ap.add_argument("--toolchain", required=True)
ap.add_argument("--udid", required=True)
ap.add_argument("--port", default="5568")
a = ap.parse_args()
P = os.path.abspath(a.project)
T = os.path.join(os.path.dirname(os.path.abspath(__file__)), "template")

# the wizard's own module sources and build files go; the wrapper, gradle.properties and iosApp stay
for rel in ("shared/src", "core/src", "build-logic"):
    shutil.rmtree(os.path.join(P, rel), ignore_errors=True)
for rel in ("shared/build.gradle.kts", "core/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml"):
    p = os.path.join(P, rel)
    if os.path.exists(p):
        os.remove(p)

for root, _, files in os.walk(T):
    for f in files:
        src = os.path.join(root, f)
        dst = os.path.join(P, os.path.relpath(src, T))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        text = open(src).read().replace("@PLUGIN_REPO@", a.plugin_repo).replace("@PLUGIN_VERSION@", a.plugin_version)
        open(dst, "w").write(text)

with open(os.path.join(P, "local.properties"), "w") as f:
    f.write(f"cmp.hotreload.toolchainDir={a.toolchain}\ncmp.hotreload.simulatorUdid={a.udid}\ncmp.hotreload.port={a.port}\n")
print(f"convention-plugins project ready in {os.path.basename(P)} (plugin {a.plugin_version})")
