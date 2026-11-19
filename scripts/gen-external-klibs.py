#!/usr/bin/env python3
"""Pin the public klibs the toolchain needs (interface v8): coordinate, source
repository, URL, sha256, size and license, verified against the repositories.

  gen-external-klibs.py --klibs <list> --out external-klibs.json [--not-found nf.txt]

<list> has one klib path per line as Gradle stores it:
  .../modules-2/files-2.1/<group>/<artifact>/<version>/<sha1>/<file name>
Lines outside a Gradle cache (for example the bundled Compose klibs) are skipped.

For every klib:
  1. local sha256/size; the <sha1> directory must equal the file's SHA-1;
  2. the Gradle module metadata (<artifact>-<version>.module) is read from Maven
     Central, then Google Maven; the file entry whose "name" equals the local
     file name gives the real download "url" and the published sha256/size;
  3. the file is downloaded from that URL and must match the local sha256 and
     the published sha256 (all three equal);
  4. the POM gives the license names.
Klibs found in neither repository go to "bundledKlibs" (with the local source
path, used only by pack-toolchain.sh; gen-bundle-manifest.py drops it), the
license taken from the POM in the same Gradle cache. Exit 1 only if a file does
not match (local SHA-1 != cache directory, or local != downloaded/published
sha256); --not-found lists what has to be bundled.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

REPOS = [
    ("maven-central", "https://repo1.maven.org/maven2"),
    ("google", "https://dl.google.com/dl/android/maven2"),
]
GRADLE_PATH = re.compile(r"/modules-2/files-2\.1/([^/]+)/([^/]+)/([^/]+)/([0-9a-f]{40})/([^/]+)$")
UA = {"User-Agent": "compose-ios-hot-reload-toolchain/1"}


def get(url, attempts=4):
    """GET with retries: repositories occasionally cut a transfer short."""
    import http.client
    import time
    for i in range(attempts):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError:
            raise  # 404 and friends are answers, not transient failures
        except (http.client.IncompleteRead, urllib.error.URLError, TimeoutError, ConnectionError):
            if i == attempts - 1:
                raise
            time.sleep(1 + 2 * i)


def download(url):
    """Artifacts via curl: retries and resumes transfers that get cut short."""
    import subprocess
    import tempfile
    fd, tmp = tempfile.mkstemp(prefix="extk-")
    os.close(fd)
    try:
        subprocess.run(["curl", "-fsSL", "--retry", "6", "--retry-all-errors", "--retry-delay", "2",
                        "-C", "-", "-A", UA["User-Agent"], "-o", tmp, url], check=True)
        return open(tmp, "rb").read()
    finally:
        os.unlink(tmp)


def hashes(data):
    return hashlib.sha256(data).hexdigest(), hashlib.sha1(data).hexdigest()


def licenses(repo_url, g, a, v):
    try:
        pom = get(f"{repo_url}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom")
    except Exception:
        return []
    root = ET.fromstring(pom)
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    out = []
    for lic in root.findall(".//m:licenses/m:license", ns) or root.findall(".//licenses/license"):
        name = lic.findtext("m:name", default="", namespaces=ns) or lic.findtext("name", default="")
        url = lic.findtext("m:url", default="", namespaces=ns) or lic.findtext("url", default="")
        out.append({"name": name.strip(), "url": url.strip()})
    return out


def local_pom_licenses(path, a, v):
    """POM next to the klib in the Gradle cache (files-2.1/<g>/<a>/<v>/<sha1>/<a>-<v>.pom)."""
    version_dir = os.path.dirname(os.path.dirname(path))
    for d in os.listdir(version_dir):
        pom = os.path.join(version_dir, d, f"{a}-{v}.pom")
        if os.path.exists(pom):
            root = ET.parse(pom).getroot()
            out = []
            for lic in root.iter():
                if lic.tag.endswith("license"):
                    name = next((c.text for c in lic if c.tag.endswith("name")), "") or ""
                    url = next((c.text for c in lic if c.tag.endswith("url")), "") or ""
                    out.append({"name": name.strip(), "url": url.strip()})
            return out
    return []


def resolve(g, a, v, name):
    for repo_id, repo_url in REPOS:
        base = f"{repo_url}/{g.replace('.', '/')}/{a}/{v}"
        try:
            module = json.loads(get(f"{base}/{a}-{v}.module"))
        except Exception:
            continue
        for var in module.get("variants", []):
            for f in var.get("files", []):
                if f.get("name") == name:
                    return repo_id, repo_url, f"{base}/{f['url']}", f, licenses(repo_url, g, a, v)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--klibs", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--not-found")
    a = ap.parse_args()
    entries, missing, bundled, bad = [], [], [], []
    for line in open(a.klibs):
        path = line.strip()
        m = GRADLE_PATH.search(path)
        if not m:
            continue
        g, art, ver, sha1_dir, name = m.groups()
        data = open(path, "rb").read()
        sha256, sha1 = hashes(data)
        if sha1 != sha1_dir:
            bad.append(f"{g}:{art}:{ver} {name}: local SHA-1 {sha1} != cache directory {sha1_dir}")
            continue
        r = resolve(g, art, ver, name)
        if not r:
            missing.append(f"{g}:{art}:{ver} {name}: not in Maven Central or Google Maven -> bundled")
            bundled.append({
                "coordinate": f"{g}:{art}:{ver}", "file": name,
                "path": f"bundled-klibs/{g}/{art}/{ver}/{name}",
                "sha256": sha256, "bytes": len(data),
                "license": local_pom_licenses(path, art, ver), "source": path,
            })
            print(f"bnd {bundled[-1]['coordinate']:<80} {'(bundle)':<13} {len(data):>9} {sha256[:12]}")
            continue
        repo_id, repo_url, url, meta, lic = r
        remote = download(url)
        r256, _ = hashes(remote)
        published = meta.get("sha256")
        if not (r256 == sha256 and (published in (None, sha256))):
            bad.append(f"{g}:{art}:{ver} {name}: local {sha256} remote {r256} published {published}")
            continue
        remote_file = url.rsplit("/", 1)[1]
        stem = f"{art}-{ver}"
        classifier = ""
        if remote_file.startswith(stem + "-") and remote_file.endswith(".klib"):
            classifier = remote_file[len(stem) + 1:-len(".klib")]
        entries.append({
            "coordinate": f"{g}:{art}:{ver}" + (f":{classifier}" if classifier else ""),
            "repository": repo_id, "repositoryUrl": repo_url, "url": url,
            "file": remote_file, "sha256": sha256, "bytes": len(data),
            "license": lic,
        })
        print(f"ok  {entries[-1]['coordinate']:<80} {repo_id:<13} {len(data):>9} {sha256[:12]}")
    entries.sort(key=lambda e: e["coordinate"])
    bundled.sort(key=lambda e: e["path"])
    with open(a.out, "w") as f:
        json.dump({"externalKlibs": entries, "bundledKlibs": bundled}, f, indent=2)
        f.write("\n")
    if a.not_found:
        with open(a.not_found, "w") as f:
            f.write("".join(x + "\n" for x in missing))
    for x in missing:
        print("NOT-IN-REPOS " + x, file=sys.stderr)
    for x in bad:
        print("MISMATCH " + x, file=sys.stderr)
    print(f"{len(entries)} klibs pinned for download, {len(bundled)} to bundle, {len(bad)} mismatched -> {a.out}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
