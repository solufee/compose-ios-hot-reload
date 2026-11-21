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

Several fetches of the same version (for example two Gradle builds) run one after the other: each holds
DIR/<version>/.fetch.lock while it works, and the second one finds the components done. A component is
unpacked into a fresh directory next to its target and then moved in file by file, so the target never
holds a half-written file; a failed unpack is retried once (FETCH_EXTRACT_ATTEMPTS, default 2), shows the
zstd/tar error, leaves the verified archive in .download for the next run, and marks nothing as done.

Exit codes: 0 ok; 2 bad manifest or arguments; 3 size/sha256 mismatch;
4 missing tool (zstd) or extraction failure; 5 download failure.
"""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
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


RETRYABLE_HTTP = {408, 429, 500, 502, 503, 504}


def fetch_bytes(url, accept=None, attempts=None, backoff=None):
    """GET with retries and exponential backoff (FETCH_RETRIES, default 4 attempts;
    FETCH_BACKOFF, default 1 s: waits 1, 2, 4 s). Transient failures (a transfer cut
    short, timeouts, connection errors, HTTP 408/429/5xx) are retried; other HTTP
    errors (404 ...) are not. A final failure exits 5 with a short message."""
    import http.client
    import time
    attempts = attempts or int(os.environ.get("FETCH_RETRIES", "4"))
    backoff = float(os.environ.get("FETCH_BACKOFF", "1")) if backoff is None else backoff
    last = None
    for i in range(1, attempts + 1):
        try:
            with open_url(url, accept) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code not in RETRYABLE_HTTP:
                die(5, f"下载失败（服务器返回 {e.code}）/ download failed (HTTP {e.code}): {url}")
        except (http.client.IncompleteRead, urllib.error.URLError, TimeoutError, ConnectionError,
                OSError) as e:
            last = type(e).__name__ + (f": {e.reason}" if getattr(e, "reason", None) else "")
        if i < attempts:
            wait = backoff * (2 ** (i - 1))
            print(f"fetch: 下载中断（{last}），{wait:g} s 后重试（第 {i + 1}/{attempts} 次）/ "
                  f"download interrupted ({last}), retrying in {wait:g} s (attempt {i + 1}/{attempts})",
                  file=sys.stderr)
            time.sleep(wait)
    die(5, f"下载失败（已重试 {attempts} 次，{last}）/ download failed after {attempts} attempts ({last}): {url}\n"
           "       请检查网络后重新运行 / check the network and run again")


def read_manifest(src):
    if src.startswith("https://"):
        data = fetch_bytes(src)
    elif src.startswith("file://"):
        data = open(urllib.parse.unquote(src[len("file://"):]), "rb").read()
    else:
        data = open(src, "rb").read()
    try:
        return json.loads(data)
    except ValueError:
        die(2, f"清单不是有效的 JSON / the manifest is not valid JSON: {src}")


def resolve_release_asset(d):
    """Private repos: browser download URLs need the API asset endpoint."""
    api = f"https://api.github.com/repos/{d['repo']}/releases/tags/{urllib.parse.quote(d['tag'])}"
    rel = json.loads(fetch_bytes(api, "application/vnd.github+json"))
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
        die(5, f"{comp['id']}: 下载失败 / download failed from {url}: {e}\n"
               "       请检查网络后重新运行（已下载并校验过的组件会跳过）/ check the network and run again "
               "(verified components are skipped)")
    size = os.path.getsize(tmp)
    digest = sha256(tmp)
    if size != comp["bytes"] or digest != comp["sha256"]:
        os.remove(tmp)
        die(3, f"{comp['id']}: got {size} bytes sha256 {digest}, manifest says "
               f"{comp['bytes']} bytes sha256 {comp['sha256']}; deleted, not using it")
    os.replace(tmp, dest)


def verified(path, comp):
    return os.path.isfile(path) and os.path.getsize(path) == comp["bytes"] and sha256(path) == comp["sha256"]


def unpack(archive, into):
    """zstd -dc ARCHIVE | tar -xf - -C INTO. Returns None or the error text of zstd/tar."""
    with tempfile.TemporaryFile() as zerr, tempfile.TemporaryFile() as terr:
        z = subprocess.Popen(["zstd", "-dc", archive], stdout=subprocess.PIPE, stderr=zerr)
        t = subprocess.run(["tar", "-xf", "-", "-C", into], stdin=z.stdout, stderr=terr)
        z.stdout.close()
        zrc = z.wait()
        if zrc == 0 and t.returncode == 0:
            return None
        zerr.seek(0)
        terr.seek(0)
        text = (zerr.read() + terr.read()).decode("utf-8", "replace").strip().replace("\n", " | ")
        return f"zstd exit {zrc}, tar exit {t.returncode}" + (f": {text[:400]}" if text else "")


def move_tree(src, dst):
    """Move every file and symlink of SRC into DST (os.replace, same file system), keeping what DST already
    has from other components; directories take the mode they have in the archive."""
    modes = []
    for dirpath, dirnames, filenames in os.walk(src):
        rel = os.path.relpath(dirpath, src)
        out = os.path.normpath(os.path.join(dst, rel))
        if os.path.lexists(out) and not os.path.isdir(out):
            os.remove(out)
        os.makedirs(out, exist_ok=True)
        os.chmod(out, os.stat(out).st_mode | 0o700)  # an earlier run may have left it read-only
        modes.append((out, os.stat(dirpath).st_mode & 0o7777))
        os.chmod(dirpath, os.stat(dirpath).st_mode | 0o700)  # so entries can be moved out
        for name in filenames + [d for d in dirnames if os.path.islink(os.path.join(dirpath, d))]:
            target = os.path.join(out, name)
            if os.path.isdir(target) and not os.path.islink(target):
                shutil.rmtree(target)
            os.replace(os.path.join(dirpath, name), target)
    for out, mode in reversed(modes):
        os.chmod(out, mode | 0o200)


def extract(archive, target, comp_id="component"):
    """Unpack into a fresh directory next to TARGET, then move the files in. Retried once."""
    attempts = max(1, int(os.environ.get("FETCH_EXTRACT_ATTEMPTS", "2")))
    parent = os.path.dirname(os.path.abspath(target))
    os.makedirs(parent, exist_ok=True)
    error = None
    for i in range(1, attempts + 1):
        tmp = tempfile.mkdtemp(prefix=f".extract-{comp_id}-", dir=parent)
        try:
            error = unpack(archive, tmp)
            if error is None:
                os.makedirs(target, exist_ok=True)
                move_tree(tmp, target)
                return
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if i < attempts:
            print(f"fetch: {comp_id}: 解包失败，重试（第 {i + 1}/{attempts} 次）/ unpack failed ({error}), "
                  f"retrying (attempt {i + 1}/{attempts})", file=sys.stderr)
    die(4, f"{comp_id}: 解包失败 / extracting {os.path.basename(archive)} into {target} failed after "
           f"{attempts} attempts: {error}\n"
           f"       已校验的下载文件保留在 {archive}，重新运行会再校验后直接解包 / the verified archive is kept "
           "there; run again to check it and unpack it again (nothing was marked as done)")


def fetch_components(m, root):
    """Download, verify and unpack every component not yet done (DIR/<version>/.fetched/<id>)."""
    dl_dir = os.path.join(root, ".download")
    marks = os.path.join(root, ".fetched")
    for c in m["components"]:
        mark = os.path.join(marks, c["id"])
        if os.path.exists(mark) and open(mark).read().strip() == c["sha256"]:
            print(f"cached {c['id']}")
            continue
        os.makedirs(dl_dir, exist_ok=True)
        os.makedirs(marks, exist_ok=True)
        archive = os.path.join(dl_dir, c["file"])
        if verified(archive, c):
            print(f"reusing {c['id']} (already downloaded, sha256 ok)")
        else:
            download(c, archive)
        extract(archive, os.path.join(root, c["extractDir"]), c["id"])
        with open(mark + ".tmp", "w") as f:
            f.write(c["sha256"])
        os.replace(mark + ".tmp", mark)
        os.remove(archive)
        print(f"fetched {c['id']} (sha256 {c['sha256'][:12]} ok)")


@contextlib.contextmanager
def version_lock(root):
    """One fetch per toolchain version at a time; leftovers of an interrupted unpack are removed."""
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, ".fetch.lock"), "w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(f"fetch: 另一个 fetch 正在处理 {root}，等待它结束 / another fetch is working on this version, "
                  "waiting for it", file=sys.stderr)
            fcntl.flock(lock, fcntl.LOCK_EX)
        for name in os.listdir(root):
            if name.startswith(".extract-"):
                shutil.rmtree(os.path.join(root, name), ignore_errors=True)
        yield


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
    # .11: the ready marker is a property of the published build, so it is taken from the bundle manifest as is
    # (present there only when that build prints it; INTERFACE §6.1).
    marker = m.get("runtime", {}).get("readyMarker")
    if marker:
        local["runtime"]["readyMarker"] = marker
    else:
        local["runtime"].pop("readyMarker", None)
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
    if a.dry_run:
        marks = os.path.join(root, ".fetched")
        for c in m["components"]:
            mark = os.path.join(marks, c["id"])
            done = os.path.exists(mark) and open(mark).read().strip() == c["sha256"]
            print(f"{'cached' if done else 'fetch '} {c['id']:<26} {c['bytes']:>10} B  {c['download']['url']}"
                  f" -> {os.path.join(root, c['extractDir'])}")
    with (contextlib.nullcontext() if a.dry_run else version_lock(root)):
        if not a.dry_run:
            fetch_components(m, root)
        fetch_rest(a, m, root)


def fetch_rest(a, m, root):
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
    try:
        main()
    except KeyboardInterrupt:
        die(130, "已中断 / interrupted")
    except Exception as e:  # noqa: BLE001 - a short message instead of a stack trace
        if os.environ.get("FETCH_DEBUG") == "1":
            raise
        die(1, f"出错 / failed: {type(e).__name__}: {e}\n"
               "       设 FETCH_DEBUG=1 可以看到完整堆栈 / set FETCH_DEBUG=1 for the full traceback")
