#!/bin/bash
# Full P1 acceptance on a fresh clone (run before merging the plugin branch):
#   package + launch (EH OK) → ui 20 / app 20 / both 3 → multi-file 3 → AXe Next/Back x3
#   → cold start 10 (EH OK each) → three modules 5 each + all-at-once 3 → release check.
# Usage: HR_UDID=<udid> ./accept-p1-full.sh <tag>      (writes accept-plugin-p1-<tag>-*.txt)
# Stops only this project's own processes (hotReloadDaemonStop, simctl terminate by bundle id).
set -u
cd "$(dirname "$0")"
TAG=${1:?tag}
UDID=${HR_UDID:?HR_UDID}
export HR_UDID UDID
EX=$(pwd)
THREE=$EX/../gradle-plugin/test-projects/three-modules
P=accept-plugin-p1-$TAG
H_DIR=$(cd ~ && pwd)   # home directory, replaced by a placeholder in the evidence files
REPO_DIR=$(cd "$(dirname "$0")/.." && pwd)   # this repository, replaced by <REPO> in the evidence files
clean() { sed -e "s#${REPO_DIR}#<REPO>#g" -e "s#${H_DIR}#<HOME>#g"; }
# restore only the module source folders the tests edit
restore() { (cd "$1" && git checkout -q -- */src && echo "# sources restored: $(git status --short -- */src | wc -l | tr -d ' ') files under */src differ from git"); }
bid() { /usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$1/Info.plist" 2>/dev/null; }

{
  echo "# commit $(git rev-parse --short HEAD), $(date '+%F %T'), simulator $UDID"
  python3 -c "import json,sys;d=json.load(open(sys.argv[1]+'/toolchain.json'));print('# toolchain: interfaceVersion',d['interfaceVersion'],'layout',d.get('layout'),'kotlin',d['kotlin']['patchSha256'][:8],'llvm',d['llvm']['patchSha256'][:8],'kaldo',d['llvm']['kaldoIossimSha256'][:8],'compose',d['compose']['patchSha256'][:8])" \
    "$(grep '^cmp.hotreload.toolchainDir=' local.properties | cut -d= -f2-)"
  echo "## clean state"
  ./gradlew -q hotReloadDaemonStop --console=plain 2>&1 | clean
  xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null
  rm -rf build/hotreload
  echo "## package"
  ./gradlew hotReloadPackage --console=plain 2>&1 | grep -E 'toolchain OK|bundle check|package:|BUILD|wrong|rror' | clean
  echo "## run"
  ./gradlew hotReloadRun --console=plain 2>&1 | grep -E 'app started|BUILD|wrong' | clean
  grep -E 'EH check|registered .* FDE|EH OK|EH FAIL' launch.log | tail -4
} > $P-launch.txt 2>&1

python3 accept-gradle.py launch.log 20 > $P-matrix.txt 2>&1;   restore "$EX" >> $P-matrix.txt
python3 accept-multifile.py launch.log > $P-multifile.txt 2>&1; restore "$EX" >> $P-multifile.txt
python3 accept-navigation.py > $P-navigation.txt 2>&1
python3 accept-coldstart.py 10 > $P-coldstart.txt 2>&1

(  # subshell: the cd below must not leak into the release step
  echo "## stop the example app (same port as HR3)"
  xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null; echo "terminated com.example.composehr"
  cd "$THREE"
  "$EX/gradlew" -p . -q hotReloadDaemonStop --console=plain 2>&1 | clean
  rm -rf build/hotreload
  "$EX/gradlew" -p . hotReloadPackage --console=plain 2>&1 | grep -E 'toolchain OK|bundle check|package:|BUILD|wrong' | clean
  "$EX/gradlew" -p . hotReloadRun --console=plain 2>&1 | grep -E 'app started|BUILD|wrong' | clean
  grep -E 'EH OK|EH FAIL' launch.log | tail -1
  python3 accept-three.py 5 2>&1
  restore "$THREE"
  B=$(bid "$(ls -d build/hotreload/*.app | head -1)")
  xcrun simctl terminate "$UDID" "$B" 2>/dev/null; echo "terminated $B"
  "$EX/gradlew" -p . -q hotReloadDaemonStop --console=plain 2>&1 | clean
) > $P-three.txt 2>&1

./accept-release.sh > $P-release.txt 2>&1
restore "$EX" >> $P-release.txt
echo "# done $(date '+%T')" >> $P-release.txt
