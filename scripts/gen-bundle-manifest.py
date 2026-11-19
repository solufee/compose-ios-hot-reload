#!/usr/bin/env python3
"""Build the published toolchain manifest (interfaceVersion 8, layout "bundle").

Inputs:
  --packaging  packaging-manifest.json written by scripts/pack-toolchain.sh
  --identity   a local toolchain.json (from scripts/gen-toolchain.sh) for the same
               build; supplies base commits, patch hashes, host and runtime info
  --repo       GitHub repo that hosts release assets and the Maven registry
  --out        output path (published file name: toolchain.json)

All paths in the output are relative to the directory a consumer extracts the
components into. No absolute local path may appear; the script refuses to write
one. Download URLs are filled in for the real channels; publish-toolchain.sh
--local-repo rewrites them to file:// for local end-to-end tests.
"""
import argparse
import datetime
import json
import sys

RELEASE_ASSET_LIMIT = 2 * 1024 ** 3
LOCAL_HOME_PREFIX = "/" + "Users" + "/"  # macOS home paths must never be published  # GitHub: each release asset must be < 2 GiB
GROUP = "com.solufee.cmp.toolchain"


def maven(repo_url, artifact, version, ext, classifier=None):
    coord = f"{GROUP}:{artifact}:{version}" + (f":{classifier}" if classifier else "") + f"@{ext}"
    name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{ext}"
    path = "/".join(GROUP.split(".") + [artifact, version, name])
    return {"type": "maven", "repository": repo_url, "coordinate": coord,
            "path": path, "url": f"{repo_url}/{path}"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--packaging", required=True)
    ap.add_argument("--identity", required=True)
    ap.add_argument("--repo", default="solufee/cmp-ios-hot-reload")
    ap.add_argument("--maven-url", default=None,
                    help="default: https://maven.pkg.github.com/<repo>")
    ap.add_argument("--llvm-dependency", default="llvm-21-aarch64-macos-essentials-97",
                    help="official Kotlin/Native LLVM dependency used as llvmHome")
    ap.add_argument("--channel", choices=("as-packed", "release"), default="as-packed",
                    help="release: publish every component as a release asset (no Maven); "
                         "as-packed: use the channel pack-toolchain.sh recorded")
    ap.add_argument("--external-klibs",
                    help="gen-external-klibs.py output: externalKlibs are downloaded by fetch-toolchain.py "
                         "from their repositories, bundledKlibs come with the bundled-klibs component")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    pk = json.load(open(a.packaging))
    ident = json.load(open(a.identity))
    if pk.get("schema") != 3:
        sys.exit("gen-bundle-manifest: packaging manifest must be schema 3 (pack-toolchain.sh)")
    if ident.get("interfaceVersion", 0) < 5:
        sys.exit("gen-bundle-manifest: identity toolchain.json must be interfaceVersion >= 5")
    want = ident["llvm"].get("kaldoIossimSha256")
    if pk["kaldoIossimSha256"] != want:
        sys.exit(f"gen-bundle-manifest: packed libKaldo sha256 {pk['kaldoIossimSha256']} != "
                 f"identity llvm.kaldoIossimSha256 {want}; repack or regenerate the identity manifest")

    ver = pk["toolchainVersion"]
    tag = f"toolchain-{ver}"
    maven_url = a.maven_url or f"https://maven.pkg.github.com/{a.repo}"
    L = pk["layout"]
    components = []
    for c in pk["components"]:
        if a.channel == "release" or c["channel"] == "release":
            if c["bytes"] >= RELEASE_ASSET_LIMIT:
                sys.exit(f"gen-bundle-manifest: {c['file']} is {c['bytes']} bytes, over the 2 GiB release asset limit")
            dl = {"type": "release", "repo": a.repo, "tag": tag, "asset": c["file"],
                  "url": f"https://github.com/{a.repo}/releases/download/{tag}/{c['file']}"}
        else:
            dl = maven(maven_url, c["id"], ver, "tar.zst")
        components.append({k: c[k] for k in ("id", "file", "extractDir", "bytes", "rawBytes", "sha256")}
                          | {"download": dl})

    kd = L["kotlinDist"]
    doc = {
        "interfaceVersion": 8,
        "layout": "bundle",
        "toolchainVersion": ver,
        "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "kotlin": {
            "version": ident["kotlin"]["version"],
            "dist": kd,
            "kotlincNative": f"{kd}/bin/kotlinc-native",
            "compilerJar": f"{kd}/konan/lib/kotlin-native-compiler-embeddable.jar",
            "klibDir": f"{kd}/klib",
            "baseCommit": ident["kotlin"]["baseCommit"],
            "patch": ident["kotlin"]["patch"],
            "patchSha256": ident["kotlin"]["patchSha256"],
        },
        "compose": {
            "version": ident["compose"]["version"],
            "pluginJar": L["composePluginJar"],
            "klibDir": L["composeKlibDir"],
            "baseCommit": ident["compose"]["baseCommit"],
            "patch": ident["compose"]["patch"],
            "patchSha256": ident["compose"]["patchSha256"],
        },
        "llvm": {
            "baseCommit": ident["llvm"]["baseCommit"],
            "patch": ident["llvm"]["patch"],
            "patchSha256": ident["llvm"]["patchSha256"],
            "kaldoHome": L["kaldoHome"],
            "kaldoIossim": L["kaldoIossim"],
            "kaldoIossimSha256": want,
            "llvmHome": {"kind": "konan-dependency", "name": a.llvm_dependency},
        },
        "host": ident["host"],
        "runtime": ident["runtime"],
        "components": components,
    }
    for key in ("llvmTools", "orcRuntime", "uikitUtils"):
        if key in L:
            doc.setdefault("tools", {})[key] = L[key]
    if a.external_klibs:
        ext = json.load(open(a.external_klibs))
        doc["externalKlibs"] = [
            {k: e[k] for k in ("coordinate", "repository", "url", "file", "sha256", "bytes", "license")}
            for e in ext.get("externalKlibs", [])]
        bundled = ext.get("bundledKlibs", [])
        if bundled and not any(c["id"] == "bundled-klibs" for c in components):
            sys.exit("gen-bundle-manifest: bundledKlibs listed but no bundled-klibs component was packed "
                     "(pack-toolchain.sh EXTERNAL_KLIBS=...)")
        # "source" (a local path used only while packing) is not published.
        doc["bundledKlibs"] = [
            {k: e[k] for k in ("coordinate", "file", "path", "sha256", "bytes", "license")} for e in bundled]

    text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
    if LOCAL_HOME_PREFIX in text or "file://" in text:
        sys.exit("gen-bundle-manifest: refusing to write a published manifest with local paths")
    open(a.out, "w").write(text)
    print(f"{a.out}: interfaceVersion 8, {len(components)} components, tag {tag}")


if __name__ == "__main__":
    main()
