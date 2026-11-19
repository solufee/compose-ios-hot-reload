#!/usr/bin/env python3
"""Publishing scaffold for the prebuilt toolchain (interfaceVersion 8).

  publish-toolchain.py --dist-dir DIR --manifest toolchain.json            # dry run (default)
  publish-toolchain.py --dist-dir DIR --manifest toolchain.json --local-repo REPO_DIR
  publish-toolchain.py --dist-dir DIR --manifest toolchain.json --publish --confirm-repo OWNER/REPO

Both modes first validate everything a real upload would rely on:
  - the manifest is interfaceVersion 8, layout "bundle", with no local paths;
  - every component file exists in --dist-dir with the manifest's size and sha256;
  - release assets are under GitHub's 2 GiB limit.

Dry run prints the exact commands a real publish would run (gh release create /
upload for release assets, an authenticated PUT to the GitHub Packages Maven
registry for Maven artifacts) and executes none of them.

--local-repo copies every file into a local mirror of the remote layout:
  REPO_DIR/releases/<owner>/<repo>/download/<tag>/<asset>
  REPO_DIR/maven/<group path>/<artifact>/<version>/<file> (+ a minimal .pom)
and writes REPO_DIR/toolchain-<version>.json: the same manifest with every
download.url rewritten to file://, for fetch-toolchain.py end-to-end tests.

--publish is the only mode that uploads. It needs the explicit flag and
--confirm-repo equal to the repo in the manifest, runs the same validation
first, refuses an existing tag, then runs `gh release create --draft` and
`gh release upload` (release assets + this manifest). The release stays a
draft; making it public is a separate, manual `gh release edit <tag>
--draft=false`. Maven components are not uploaded by --publish (0.1.0 ships
every component as a release asset: gen-bundle-manifest.py --channel release).
"""
import argparse
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys

RELEASE_ASSET_LIMIT = 2 * 1024 ** 3
LOCAL_HOME_PREFIX = "/" + "Users" + "/"  # macOS home paths must never be published


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def validate(dist_dir, manifest_path):
    text = open(manifest_path).read()
    m = json.loads(text)
    errors = []
    if m.get("interfaceVersion") != 8 or m.get("layout") != "bundle":
        errors.append("manifest must be interfaceVersion 8 with layout \"bundle\"")
    if LOCAL_HOME_PREFIX in text or "file://" in text:
        errors.append("manifest contains local paths or file:// URLs")
    for c in m.get("components", []):
        p = os.path.join(dist_dir, c["file"])
        if not os.path.isfile(p):
            errors.append(f"{c['id']}: missing {c['file']}")
            continue
        size = os.path.getsize(p)
        if size != c["bytes"]:
            errors.append(f"{c['id']}: size {size} != manifest {c['bytes']}")
        digest = sha256(p)
        if digest != c["sha256"]:
            errors.append(f"{c['id']}: sha256 {digest} != manifest {c['sha256']}")
        if c["download"]["type"] == "release" and size >= RELEASE_ASSET_LIMIT:
            errors.append(f"{c['id']}: {size} bytes exceeds the 2 GiB release asset limit")
    if errors:
        for e in errors:
            print("publish: " + e, file=sys.stderr)
        sys.exit(1)
    return m


def pom(coordinate):
    group, artifact, version = coordinate.split("@")[0].split(":")[:3]
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
            f'  <modelVersion>4.0.0</modelVersion>\n'
            f'  <groupId>{group}</groupId>\n  <artifactId>{artifact}</artifactId>\n'
            f'  <version>{version}</version>\n  <packaging>pom</packaging>\n</project>\n')


def dry_run(m, dist_dir, manifest_path):
    q = shlex.quote
    rel = [c for c in m["components"] if c["download"]["type"] == "release"]
    mvn = [c for c in m["components"] if c["download"]["type"] == "maven"]
    print("# DRY RUN: nothing below is executed. Needs a gh login with repo scope;")
    print("# Maven uploads need a token with write:packages in $GITHUB_TOKEN.")
    if rel:
        repo, tag = rel[0]["download"]["repo"], rel[0]["download"]["tag"]
        print(f"gh release view {q(tag)} --repo {q(repo)} >/dev/null 2>&1 && "
              f"{{ echo 'tag exists; bump the toolchain revision instead of overwriting'; exit 1; }}")
        print(f"gh release create {q(tag)} --repo {q(repo)} --draft "
              f"--title {q('Toolchain ' + m['toolchainVersion'])} "
              f"--notes {q('kotlin ' + m['kotlin']['patchSha256'][:12] + ' / llvm ' + m['llvm']['patchSha256'][:12] + ' / compose ' + m['compose']['patchSha256'][:12])}")
        files = " ".join(q(os.path.join(dist_dir, c["file"])) for c in rel)
        print(f"gh release upload {q(tag)} --repo {q(repo)} {files} {q(manifest_path)}")
    for c in mvn:
        d = c["download"]
        base = d["url"].rsplit("/", 1)[0]
        name = d["path"].rsplit("/", 1)[1]
        pom_name = name.rsplit(".", 2)[0] + ".pom" if name.endswith(".tar.zst") else name + ".pom"
        print(f"curl --fail -u \"$GITHUB_ACTOR:$GITHUB_TOKEN\" -T {q(os.path.join(dist_dir, c['file']))} {q(base + '/' + name)}")
        print(f"curl --fail -u \"$GITHUB_ACTOR:$GITHUB_TOKEN\" -T <generated {pom_name}> {q(base + '/' + pom_name)}")
    total = sum(c["bytes"] for c in m["components"])
    print(f"# {len(rel)} release assets + manifest, {len(mvn)} Maven artifacts, {total/2**20:.1f} MiB total")


def local_repo(m, dist_dir, repo_dir):
    repo_dir = os.path.abspath(repo_dir)
    local = json.loads(json.dumps(m))
    for c in local["components"]:
        d = c["download"]
        if d["type"] == "release":
            dest = os.path.join(repo_dir, "releases", d["repo"], "download", d["tag"], d["asset"])
        else:
            dest = os.path.join(repo_dir, "maven", d["path"])
            pom_path = dest[: -len(".tar.zst")] + ".pom" if dest.endswith(".tar.zst") else dest + ".pom"
            os.makedirs(os.path.dirname(pom_path), exist_ok=True)
            open(pom_path, "w").write(pom(d["coordinate"]))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.copyfile(os.path.join(dist_dir, c["file"]), dest)
        d["url"] = "file://" + dest
        print(f"  {c['id']:<26} -> {os.path.relpath(dest, repo_dir)}")
    out = os.path.join(repo_dir, f"toolchain-{m['toolchainVersion']}.json")
    with open(out, "w") as f:
        json.dump(local, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"local manifest (file:// URLs): {out}")


def publish(m, dist_dir, manifest_path, confirm_repo):
    rel = [c for c in m["components"] if c["download"]["type"] == "release"]
    mvn = [c for c in m["components"] if c["download"]["type"] == "maven"]
    if mvn:
        sys.exit("publish: --publish uploads release assets only; regenerate the manifest with "
                 "gen-bundle-manifest.py --channel release (Maven: " + ", ".join(c["id"] for c in mvn) + ")")
    repos = {c["download"]["repo"] for c in rel}
    tags = {c["download"]["tag"] for c in rel}
    if len(repos) != 1 or len(tags) != 1:
        sys.exit(f"publish: components must share one repo and tag, got {sorted(repos)} {sorted(tags)}")
    repo, tag = repos.pop(), tags.pop()
    if confirm_repo != repo:
        sys.exit(f"publish: --confirm-repo {confirm_repo!r} does not match the manifest repo {repo!r}")
    if subprocess.run(["gh", "release", "view", tag, "--repo", repo],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        sys.exit(f"publish: {repo} already has a release {tag}; bump the toolchain revision instead of overwriting")
    notes = ("kotlin " + m["kotlin"]["patchSha256"][:12] + " / llvm " + m["llvm"]["patchSha256"][:12]
             + " / compose " + m["compose"]["patchSha256"][:12])
    cmd = ["gh", "release", "create", tag, "--repo", repo, "--draft",
           "--title", "Toolchain " + m["toolchainVersion"], "--notes", notes]
    print("publish: " + " ".join(shlex.quote(x) for x in cmd))
    subprocess.run(cmd, check=True)
    files = [os.path.join(dist_dir, c["file"]) for c in rel] + [manifest_path]
    cmd = ["gh", "release", "upload", tag, "--repo", repo] + files
    print("publish: gh release upload " + tag + f" ({len(files)} files)")
    subprocess.run(cmd, check=True)
    print(f"published draft release {tag} on {repo}; make it public with: "
          f"gh release edit {shlex.quote(tag)} --repo {shlex.quote(repo)} --draft=false")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dist-dir", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--local-repo")
    ap.add_argument("--publish", action="store_true",
                    help="really upload (draft GitHub release); needs --confirm-repo")
    ap.add_argument("--confirm-repo", help="must equal the repo in the manifest")
    a = ap.parse_args()
    if a.publish and a.local_repo:
        sys.exit("publish: --publish and --local-repo are exclusive")
    if a.publish and not a.confirm_repo:
        sys.exit("publish: --publish needs --confirm-repo OWNER/REPO")
    m = validate(a.dist_dir, a.manifest)
    print(f"validated {len(m['components'])} components for {m['toolchainVersion']}")
    if a.local_repo:
        local_repo(m, a.dist_dir, a.local_repo)
    elif a.publish:
        publish(m, a.dist_dir, a.manifest, a.confirm_repo)
    else:
        dry_run(m, a.dist_dir, a.manifest)


if __name__ == "__main__":
    main()
