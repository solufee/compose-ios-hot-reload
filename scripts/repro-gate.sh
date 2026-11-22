#!/bin/bash
# Reproducibility gate for toolchain releases (Issue #96).
#
# Runs scripts/pack-toolchain.sh twice, in parallel, each in its own working directory with its own
# TMPDIR and output dir, from the same inputs (all pack-toolchain.sh variables are passed through).
# Every file of the two outputs (each archive and packaging-manifest.json) must have the same sha256;
# otherwise the gate fails and nothing is written to OUT_DIR. On success build A becomes OUT_DIR, its
# packaging manifest gets a "reproducibility" record (gen-bundle-manifest.py --channel release refuses
# a manifest without one), and OUT_DIR/repro-gate.txt lists both builds' sha256 values.
#
# Required: OUT_DIR (must not exist yet) and the pack-toolchain.sh inputs.
# Optional: GATE_WORK  parent dir of the two builds (default: a new temp dir, removed afterwards)
set -u
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
OUT_DIR=${OUT_DIR:?OUT_DIR is required}
[ -e "$OUT_DIR" ] && { echo "repro-gate: $OUT_DIR already exists" >&2; exit 1; }
KEEP=${GATE_WORK:+1}
GATE_WORK=${GATE_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/repro-gate.XXXXXX")}
mkdir -p "$GATE_WORK"
[ -n "$(ls -A "$GATE_WORK")" ] && { echo "repro-gate: $GATE_WORK is not empty" >&2; exit 1; }

for b in a b; do mkdir -p "$GATE_WORK/$b/out" "$GATE_WORK/$b/tmp" "$GATE_WORK/$b/cwd"; done
echo "repro-gate: two builds in parallel ($(date +%T))"
pids=()
for b in a b; do
  ( cd "$GATE_WORK/$b/cwd" && env OUT_DIR="$GATE_WORK/$b/out" TMPDIR="$GATE_WORK/$b/tmp" \
      bash "$SCRIPT_DIR/pack-toolchain.sh" ) > "$GATE_WORK/$b/pack.out" 2>&1 &
  pids+=($!)
done
rc=0
for i in 0 1; do wait "${pids[$i]}" || { rc=1; echo "repro-gate: build $([ $i = 0 ] && echo a || echo b) failed:" >&2; tail -5 "$GATE_WORK/$([ $i = 0 ] && echo a || echo b)/pack.out" >&2; }; done
[ $rc = 0 ] || exit 1
echo "repro-gate: both builds done ($(date +%T))"

sums() { ( cd "$1" && find . -type f | LC_ALL=C sort | xargs shasum -a 256 ); }
sums "$GATE_WORK/a/out" > "$GATE_WORK/a.sha"
sums "$GATE_WORK/b/out" > "$GATE_WORK/b.sha"
if ! cmp -s "$GATE_WORK/a.sha" "$GATE_WORK/b.sha"; then
  echo "repro-gate: FAIL, the two builds differ (nothing written to $OUT_DIR):" >&2
  diff "$GATE_WORK/a.sha" "$GATE_WORK/b.sha" >&2
  echo "repro-gate: builds kept in $GATE_WORK" >&2
  exit 1
fi
n=$(wc -l < "$GATE_WORK/a.sha" | tr -d ' ')

python3 - "$GATE_WORK/a/out/packaging-manifest.json" "$n" <<'PY'
import json, sys
p, n = sys.argv[1], int(sys.argv[2])
m = json.load(open(p))
m["reproducibility"] = {
    "gate": "scripts/repro-gate.sh",
    "builds": 2,
    "parallel": True,
    "separateWorkDirs": True,
    "comparedFiles": n,
    "result": "identical",
}
with open(p, "w") as f:
    json.dump(m, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY
mkdir -p "$(dirname "$OUT_DIR")"
mv "$GATE_WORK/a/out" "$OUT_DIR"
{
  echo "repro-gate: PASS, $n files identical in two parallel builds (separate working dir, TMPDIR and output dir each)"
  echo "== build A"; cat "$GATE_WORK/a.sha"
  echo "== build B"; cat "$GATE_WORK/b.sha"
  echo "== packaging-manifest.json after adding the reproducibility record"
  ( cd "$OUT_DIR" && shasum -a 256 packaging-manifest.json )
} > "$OUT_DIR/repro-gate.txt"
cp "$GATE_WORK/a/pack.out" "$OUT_DIR/pack-a.out"; cp "$GATE_WORK/b/pack.out" "$OUT_DIR/pack-b.out"
[ -n "$KEEP" ] || rm -rf "$GATE_WORK"
head -1 "$OUT_DIR/repro-gate.txt"
