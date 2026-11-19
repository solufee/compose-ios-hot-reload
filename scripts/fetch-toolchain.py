#!/usr/bin/env python3
"""Reference consumer for the published toolchain (interfaceVersion 6-8; the bundle format is unchanged since 6).

  fetch-toolchain.py MANIFEST --cache DIR [--llvm-home official|PATH]
                     [--extra-klibs FILE] [--auto-cache-from DIRS] [--dry-run]

MANIFEST is a path, file:// URL or https:// URL of a layout "bundle" toolchain.json.
For every component: download into DIR/<version>/.download, check size and
sha256 (a mismatch deletes the file and exits 3; there is no fallback to an
unverified copy), then extract with zstd + tar into DIR/<version>/<extractDir>.

Afterwards the toolchain is localized:
  - kotlin-native/konan/konan.properties: <KALDO_INSTALL> -> DIR/<version>/kaldo,
    llvmHome.macos_arm64 -> the official Kotlin/Native LLVM dependency
    ($llvm.macos_arm64.user, downloaded by Kotlin/Native itself) or PATH;
  - libKaldo's sha256 must equal llvm.kaldoIossimSha256;
  - public klibs (interface v8 manifests): "externalKlibs" are downloaded from
    their Maven repositories (Maven Central, Google Maven) into
    DIR/<version>/external-klibs/<group>/<artifact>/<version>/, each checked
    against the manifest's size and sha256 (mismatch: deleted, exit 3);
    "bundledKlibs" come with the bundled-klibs component and are checked too;
  - DIR/<version>/klibs.txt: the Compose klibs, the public klibs, plus
    --extra-klibs; nothing is read from a Gradle cache;
  - DIR/<version>/<dist>/toolchain.json: local manifest (layout "local", absolute
    paths), where hotreload.sh looks for it ($KOTLIN_DIST/toolchain.json);
  - DIR/<version>/env.sh: exports for example-gradle/hotreload.sh and run.sh.

Downloads: file:// and https:// (via curl, with retries and resume). Public
GitHub releases and Maven repositories need no token. If $GITHUB_TOKEN is set it
is sent for release assets; release assets of a private repo are resolved through the
GitHub API (releases/tags/<tag> -> asset id). The https paths are written but
have not been exercised yet (nothing is published); file:// is what the B6
end-to-end test uses.

Exit codes: 0 ok; 2 bad manifest or arguments; 3 size/sha256 mismatch;
4 missing tool (zstd) or extraction failure; 5 download failure.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def die(code, msg):
    print("fetch: " + msg, file=sys.stderr)
    sys.exit(code)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def open_url(url, accept=None):
    req = urllib.request.Request(url)
    tok = os.environ.get("GITHUB_TOKEN")
    if tok and url.startswith("https://"):
        req.add_header("Authorization", f"Bearer {tok}")
    if accept:
        req.add_header("Accept", accept)
    return urllib.request.urlopen(req, timeout=120)


def read_manifest(src):
    if src.startswith(("file://", "https://")):
        with open_url(src) as r:
            return json.loads(r.read())
    return json.load(open(src))


def resolve_release_asset(d):
    """Private repos: browser download URLs need the API asset endpoint."""
    api = f"https://api.github.com/repos/{d['repo']}/releases/tags/{urllib.parse.quote(d['tag'])}"
    with open_url(api, "application/vnd.github+json") as r:
        rel = json.loads(r.read())
    for asset in rel.get("assets", []):
        if asset["name"] == d["asset"]:
            return asset["url"], "application/octet-stream"
    die(5, f"release {d['tag']} has no asset {d['asset']}")


def curl(url, tmp, accept=None):
    cmd = ["curl", "-fsSL", "--retry", "6", "--retry-all-errors", "--retry-delay", "2", "-C", "-",
           "-A", "compose-ios-hot-reload-fetch/1", "-o", tmp]
    if accept:
        cmd += ["-H", f"Accept: {accept}"]
    tok = os.environ.get("GITHUB_TOKEN")
    if tok and ("github.com" in url or "githubusercontent.com" in url):
        cmd += ["-H", f"Authorization: Bearer {tok}"]
    subprocess.run(cmd + [url], check=True)


def download(comp, dest):
    d = comp["download"]
    url, accept = d["url"], None
    if url.startswith("https://") and d["type"] == "release" and os.environ.get("GITHUB_TOKEN"):
        url, accept = resolve_release_asset(d)
    tmp = dest + ".part"
    try:
        if url.startswith("file://"):
            shutil.copyfile(urllib.parse.unquote(url[len("file://"):]), tmp)
        else:
            curl(url, tmp, accept)
    except Exception as e:  # noqa: BLE001 - report any transport error the same way
        if os.path.exists(tmp):
            os.remove(tmp)
        die(5, f"{comp['id']}: download failed from {url}: {e}")
    size = os.path.getsize(tmp)
    digest = sha256(tmp)
    if size != comp["bytes"] or digest != comp["sha256"]:
        os.remove(tmp)
        die(3, f"{comp['id']}: got {size} bytes sha256 {digest}, manifest says "
               f"{comp['bytes']} bytes sha256 {comp['sha256']}; deleted, not using it")
    os.replace(tmp, dest)


def extract(archive, target):
    os.makedirs(target, exist_ok=True)
    z = subprocess.Popen(["zstd", "-dc", archive], stdout=subprocess.PIPE)
    t = subprocess.run(["tar", "-xf", "-", "-C", target], stdin=z.stdout)
    z.stdout.close()
    if z.wait() != 0 or t.returncode != 0:
        die(4, f"extracting {os.path.basename(archive)} into {target} failed")


def localize(m, root, llvm_home, extra_klibs, auto_cache_from, public_klibs, public_roots):
    kp = os.path.join(root, m["kotlin"]["dist"], "konan", "konan.properties")
    pristine = kp + ".bundle"
    if not os.path.exists(pristine):
        shutil.copyfile(kp, pristine)
    kaldo_home = os.path.join(root, m["llvm"]["kaldoHome"])
    lines = []
    for line in open(pristine).read().splitlines():
        if line.startswith("llvmHome.macos_arm64 ="):
            line = "llvmHome.macos_arm64 = " + (
                "$llvm.macos_arm64.user" if llvm_home == "official" else os.path.abspath(llvm_home))
        line = line.replace("<KALDO_INSTALL>", kaldo_home)
        lines.append(line)
    text = "\n".join(lines) + "\n"
    if "<KALDO_INSTALL>" in text or "<YOUR_LLVM_INSTALL>" in text:
        die(4, "konan.properties still has placeholders after localizing")
    if llvm_home == "official":
        want = m["llvm"]["llvmHome"]["name"]
        user = [l for l in lines if l.startswith("llvm.macos_arm64.user")]
        if not user or want not in user[0]:
            print(f"fetch: warning: konan.properties llvm.macos_arm64.user is not {want}", file=sys.stderr)
    open(kp, "w").write(text)

    kaldo = os.path.join(root, m["llvm"]["kaldoIossim"])
    if sha256(kaldo) != m["llvm"]["kaldoIossimSha256"]:
        die(3, "libKaldo.iossim.dylib does not match llvm.kaldoIossimSha256")

    klib_dir = os.path.join(root, m["compose"]["klibDir"])
    # Only unpacked klibs (default/manifest); the component also carries a
    # licenses-* folder, which is not a library.
    klibs = sorted(os.path.join(klib_dir, n) for n in os.listdir(klib_dir)
                   if os.path.isfile(os.path.join(klib_dir, n, "default", "manifest")))
    klibs += public_klibs
    if extra_klibs:
        for line in open(extra_klibs).read().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "/klib/common/" not in line \
                    and "/klib/platform/" not in line and "/compose-source-" not in line:
                klibs.append(line)
    klib_list = os.path.join(root, "klibs.txt")
    open(klib_list, "w").write("\n".join(klibs) + "\n")

    dist = os.path.join(root, m["kotlin"]["dist"])
    plugin = os.path.join(root, m["compose"]["pluginJar"])
    out = os.path.join(dist, "toolchain.json")  # $KOTLIN_DIST/toolchain.json, as hotreload.sh expects
    env = dict(os.environ, KOTLIN_DIST=dist, COMPOSE_PLUGIN_JAR=plugin, KLIBLIST=klib_list,
               KALDO_DYLIB=kaldo, TOOLCHAIN_JSON=out)
    subprocess.run(["bash", os.path.join(SCRIPT_DIR, "gen-toolchain.sh")], env=env, check=True,
                   stdout=subprocess.DEVNULL)
    local = json.load(open(out))
    local["layout"] = "local"
    local["toolchainVersion"] = m["toolchainVersion"]
    local["components"] = m["components"]
    for key in ("kotlin", "compose", "llvm"):  # identity comes from the published manifest
        for f in ("baseCommit", "patch", "patchSha256"):
            local[key][f] = m[key][f]
    # v8: the run-time tools, as absolute paths like every path in a local manifest,
    # so consumers (the Gradle plugin) need only the dist directory.
    if m.get("tools"):
        local["tools"] = {k: os.path.join(root, v) for k, v in m["tools"].items()}
    json.dump(local, open(out, "w"), indent=2, ensure_ascii=False)

    tools = m.get("tools", {})
    roots = [klib_dir] + public_roots + [p for p in auto_cache_from.split(":") if p]
    exports = {
        "KOTLIN_DIST": dist, "COMPOSE_PLUGIN_JAR": plugin, "KLIBLIST": klib_list,
        "KLIB_AUTO_CACHE_FROM": ":".join(roots),
    }
    if "llvmTools" in tools:
        exports["KONAN_LLVM_HOME"] = os.path.join(root, tools["llvmTools"])
    if "orcRuntime" in tools:
        exports["ORC_RUNTIME"] = os.path.join(root, tools["orcRuntime"])
    if "uikitUtils" in tools:
        exports["UIKIT_UTILS"] = os.path.join(root, tools["uikitUtils"])
    with open(os.path.join(root, "env.sh"), "w") as f:
        f.write("# generated by fetch-toolchain.py; source before hotreload.sh / run.sh\n")
        for k, v in exports.items():
            f.write(f"export {k}={json.dumps(v)}\n")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("--cache", required=True)
    ap.add_argument("--llvm-home", default="official")
    ap.add_argument("--extra-klibs")
    ap.add_argument("--auto-cache-from", default="",
                    help="extra colon-separated klib roots for per-file caches (default: none)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    m = read_manifest(a.manifest)
    if m.get("interfaceVersion") not in (6, 7, 8) or m.get("layout") != "bundle":
        die(2, "expected an interfaceVersion 6-8 manifest with layout \"bundle\"")
    if shutil.which("zstd") is None and not a.dry_run:
        die(4, "zstd not found on PATH (brew install zstd)")
    root = os.path.join(os.path.abspath(a.cache), m["toolchainVersion"])
    dl_dir = os.path.join(root, ".download")
    marks = os.path.join(root, ".fetched")
    for c in m["components"]:
        mark = os.path.join(marks, c["id"])
        done = os.path.exists(mark) and open(mark).read().strip() == c["sha256"]
        if a.dry_run:
            print(f"{'cached' if done else 'fetch '} {c['id']:<26} {c['bytes']:>10} B  {c['download']['url']}"
                  f" -> {os.path.join(root, c['extractDir'])}")
            continue
        if done:
            print(f"cached {c['id']}")
            continue
        os.makedirs(dl_dir, exist_ok=True)
        os.makedirs(marks, exist_ok=True)
        archive = os.path.join(dl_dir, c["file"])
        download(c, archive)
        extract(archive, os.path.join(root, c["extractDir"]))
        open(mark, "w").write(c["sha256"])
        os.remove(archive)
        print(f"fetched {c['id']} (sha256 {c['sha256'][:12]} ok)")
    public_klibs, public_roots = [], []
    ext_root = os.path.join(root, "external-klibs")
    for e in m.get("externalKlibs", []):
        g, art, ver = e["coordinate"].split(":")[:3]
        dest = os.path.join(ext_root, g, art, ver, e["file"])
        if a.dry_run:
            print(f"fetch  {e['coordinate']:<26} {e['bytes']:>10} B  {e['url']}")
            continue
        public_klibs.append(dest)
        if os.path.exists(dest) and os.path.getsize(dest) == e["bytes"] and sha256(dest) == e["sha256"]:
            continue
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        download({"id": e["coordinate"], "download": {"type": "maven", "url": e["url"]},
                  "bytes": e["bytes"], "sha256": e["sha256"]}, dest)
    if m.get("externalKlibs") and not a.dry_run:
        public_roots.append(ext_root)
        print(f"fetched {len(m['externalKlibs'])} public klibs from their Maven repositories (sha256 ok)")
    for b in m.get("bundledKlibs", []):
        path = os.path.join(root, b["path"])
        if a.dry_run:
            continue
        if not os.path.exists(path) or sha256(path) != b["sha256"]:
            die(3, f"bundled klib {b['path']} is missing or does not match its sha256")
        public_klibs.append(path)
    if m.get("bundledKlibs") and not a.dry_run:
        public_roots.append(os.path.join(root, "bundled-klibs"))
    if a.dry_run:
        return
    out = localize(m, root, a.llvm_home, a.extra_klibs, a.auto_cache_from, public_klibs, public_roots)
    print(f"toolchain ready: {out}")
    print(f"env: source {os.path.join(root, 'env.sh')}")


if __name__ == "__main__":
    main()
