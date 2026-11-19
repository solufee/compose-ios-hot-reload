#!/bin/bash
# release 零改动检查的判定口径验证：
#   baseline 连续构建直到看到两种 __text（最多 6 次）→ 校验（必须过）
#   → 改一行源码后校验（必须失败，且原因是 __text 不在允许集合）→ 还原后校验（必须过）
set -u
cd "$(dirname "$0")"
H_DIR=$(cd ~ && pwd)   # home directory, replaced by a placeholder in the evidence files
clean() { sed -e "s#${H_DIR}#<HOME>#g"; }
rm -f build/hotreload/release/baseline.fingerprint build/hotreload/release/baseline.text-hashes
{
  echo "# baseline (builds until both __text variants are seen)"
  ./gradlew hotReloadVerifyRelease -Pcmp.hotreload.releaseBaseline=true --console=plain 2>&1 \
    | grep -E 'release baseline build|release baseline recorded|went wrong|BUILD' | clean
  echo "# allowed __text set"; cat build/hotreload/release/baseline.text-hashes | cut -c1-12
  for i in 1 2; do
    echo "# verify $i, sources unchanged (must pass)"
    ./gradlew hotReloadVerifyRelease --console=plain 2>&1 | grep -E 'release unchanged|not among|differs|BUILD' | clean
  done
  sed -i '' 's/UI_MARK = "[^"]*"/UI_MARK = "release-neg-check"/' ui/src/main/kotlin/ui.kt
  echo "# verify after a real source change (must fail)"
  ./gradlew hotReloadVerifyRelease --console=plain 2>&1 | grep -E 'release unchanged|not among|differs|BUILD|  - ' | clean
  git checkout -q -- ui/src
  echo "# verify after restoring the source (must pass)"
  ./gradlew hotReloadVerifyRelease --console=plain 2>&1 | grep -E 'release unchanged|not among|differs|BUILD' | clean
  echo "# sources restored: $(git status --short ui/src app/src | wc -l | tr -d ' ') changed files"
} > accept-plugin-p1-release-criterion.txt 2>&1
