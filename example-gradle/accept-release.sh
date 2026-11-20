#!/bin/bash
# Release zero-change check (P1). Usage: HR_UDID=<udid> ./accept-release.sh > accept-plugin-p1-release.txt
#  1. clean state: no compile daemons, app not running, no build/hotreload
#  2. hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true   (release build before any hot reload)
#  3. a hot-reload session: hotReloadPackage, hotReloadRun, 3+3+3 reloads (daemons and app stay up)
#  4. hotReloadVerifyRelease                                          (same release build, compared)
#  5. the same release commands outside Gradle, in `env -i`, compared with the baseline
set -u
cd "$(dirname "$0")"
UDID=${HR_UDID:-booted}
say() { echo "== $*"; }
H_DIR=$(cd ~ && pwd)   # home directory, replaced by a placeholder in the evidence files
clean() { sed -e "s#${H_DIR}#<HOME>#g"; }

say "1. clean state"
./gradlew -q hotReloadDaemonStop --console=plain 2>&1 | clean
xcrun simctl terminate "$UDID" com.example.composehr 2>/dev/null
rm -rf build/hotreload
echo "daemons of this project: $(pgrep -f "HrCompileDaemon.*$(pwd)" | wc -l | tr -d ' ')"

say "2. baseline"
./gradlew hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true --console=plain 2>&1 | grep -E 'release|BUILD|wrong' | clean

say "3. hot-reload session"
SNAP=$(mktemp -d)
cp -R app/src "$SNAP/app-src" && cp -R ui/src "$SNAP/ui-src"   # the session edits sources; restore them before step 4
./gradlew hotReloadPackage --console=plain 2>&1 | grep -E 'package:|BUILD' | clean
./gradlew hotReloadRun --console=plain 2>&1 | grep -E 'app started|BUILD' | clean
python3 accept-gradle.py launch.log 3 | grep -E 'summary|ok='
echo "daemons of this project: $(pgrep -f "HrCompileDaemon" | wc -l | tr -d ' ') HrCompileDaemon processes on the machine"

rm -rf app/src ui/src && cp -R "$SNAP/app-src" app/src && cp -R "$SNAP/ui-src" ui/src && rm -rf "$SNAP"
echo "sources restored to the baseline state: $(git status --short app/src ui/src | wc -l | tr -d ' ') files differ from git"

say "4. verify inside Gradle (daemons and app still running)"
./gradlew hotReloadVerifyRelease --console=plain 2>&1 | grep -E 'release|BUILD|wrong|differ' | clean

say "5. same commands outside Gradle (env -i)"
R=build/hotreload/release
rm -rf "$R/manual" && mkdir -p "$R/manual/klib"
sed "s#/release/current/#/release/manual/#g" "$R/current/commands.sh" > "$R/manual/commands.sh"
env -i HOME="$H_DIR" PATH=/usr/bin:/bin:/usr/sbin:/sbin JAVA_HOME="${JAVA_HOME}" bash "$R/manual/commands.sh" > "$R/manual/build.out" 2>&1
echo "manual build exit=$?"
K=$R/manual/ComposeHR-release.kexe
segedit "$K" -extract __TEXT __text "$R/manual/text.bin"
nm -U -j "$K" | grep -v '^$' | sort > "$R/manual/symbols.txt"
{
  echo "text.sha256=$(shasum -a 256 "$R/manual/text.bin" | cut -d' ' -f1)"
  echo "symbols.count=$(wc -l < "$R/manual/symbols.txt" | tr -d ' ')"
} > "$R/manual/fingerprint.txt"
for f in baseline current manual; do
  printf '%-9s text=%s symbols=%s\n' "$f" \
    "$(grep text.sha256 "$R/$f/fingerprint.txt" | cut -c13-24)" "$(grep symbols.count "$R/$f/fingerprint.txt" | cut -d= -f2)"
done
diff <(size -m "$R/baseline/ComposeHR-release.kexe" | grep -E 'Segment|Section') <(size -m "$K" | grep -E 'Segment|Section') > /dev/null \
  && echo "manual vs baseline: section sizes identical" || echo "manual vs baseline: section sizes DIFFER"
cmp -s "$R/baseline/ComposeHR-release.symbols.txt" "$R/manual/symbols.txt" \
  && echo "manual vs baseline: symbol table identical" || echo "manual vs baseline: symbol table DIFFERS"
# Criterion (0.2.0): the GOT-order-independent __text digest equals the baseline's (raw __text hash recorded only).
MD=$(python3 -I ../scripts/release-text-digest.py "$K" | cut -d' ' -f1)
BD=$(grep '^text.digest=' "$R/baseline.fingerprint" | cut -d= -f2)
echo "manual raw __text $(shasum -a 256 "$R/manual/text.bin" | cut -c1-12), baseline raw __text $(grep '^text.sha256=' "$R/baseline.fingerprint" | cut -c13-24) (recorded only)"
[ -n "$MD" ] && [ "$MD" = "$BD" ] \
  && echo "manual vs baseline: __text digest ${MD:0:12} identical" \
  || echo "manual vs baseline: __text digest ${MD:0:12} DIFFERS from ${BD:0:12}"
