#!/bin/bash
# P0 / Issue #110 (plugin 0.2.10): a project whose libraries are newer than the toolchain's copies, in the shape of the
# user's report (botas: Koin + Compose + kotlinx-serialization 1.12.0, json and core), plus one library that is older
# in the project than in the toolchain (kotlinx-datetime 0.6.0; toolchain .11.x: 0.7.1).
#
#   accept-klib-max-110.sh PROJECT_DIR WIZARD_ZIP PLUGIN_REPO PLUGIN_VERSION TOOLCHAIN_DIR UDID [ROUNDS]
#
# 1. wizard project -> convention-plugin project (setup.py) -> the #91 shape (:core, :domain, :shared, @Configuration)
# 2. kotlinxSerialization = 1.12.0; :core depends on kotlinx-datetime 0.6.0; :core gets Stamp.kt: a @Serializable class
#    with a kotlin.time.Instant field (core's built-in InstantSerializer, core >= 1.9.0), a decode of JSON without the
#    required field (must end in SerializationException), a LocalDate; the app's BOTAS line prints all three
# 3. accept-dup-91.py --rounds ROUNDS: clean package (tiers, hints in tier 1), cold start, HOT edits in every module,
#    ROUNDS HOT rounds; the plugin 0.2.9 rule (toolchain copies always) fails to compile Stamp.kt
#    ("serializer was not found for type 'Instant'"), the 0.2.10 rule links serialization 1.12.0 from the project.
set -eu
P=$(mkdir -p "$1" && cd "$1" && pwd); ZIP=$2; REPO=$3; V=$4; TC=$5; U=$6; ROUNDS=${7:-40}
HERE=$(cd "$(dirname "$0")" && pwd)
rm -rf "$P"; mkdir -p "$P"; ( cd "$P" && unzip -q "$ZIP" ); inner=$(ls "$P"); mv "$P/$inner" "$P.tmp"; rmdir "$P"; mv "$P.tmp" "$P"; chmod +x "$P/gradlew"
python3 "$HERE/setup.py" "$P" --plugin-repo "$REPO" --plugin-version "$V" --toolchain "$TC" --udid "$U" --port 5596
python3 - "$HERE/accept-dup-91.py" "$P" <<'PY'
import sys
src = open(sys.argv[1]).read(); cut = src.index("# ---------------------------------------------------------------- 1 clean package")
sys.argv = [sys.argv[1], sys.argv[2], "--udid", "none"]
exec(compile(src[:cut], "accept-dup-91-shape", "exec"))
PY
sed -i '' 's/^kotlinxSerialization = .*/kotlinxSerialization = "1.12.0"/' "$P/gradle/libs.versions.toml"
cat >> "$P/core/build.gradle.kts" <<'EOF'

// #110: a library older in the project than in the toolchain (toolchain: kotlinx-datetime 0.7.1)
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    }
}
EOF
cat > "$P/core/src/commonMain/kotlin/org/example/core/Stamp.kt" <<'EOF'
package org.example.core

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** #110: kotlin.time.Instant uses kotlinx-serialization-core's built-in InstantSerializer (core 1.9.0+). */
@OptIn(ExperimentalTime::class)
@Serializable
data class Stamp(val at: Instant, val label: String)

@OptIn(ExperimentalTime::class)
fun stampJson(): String = Json.encodeToString(Stamp.serializer(), Stamp(Instant.fromEpochSeconds(1_700_000_000), "s1"))

/** Decoding JSON without the required fields must end in SerializationException (MissingFieldException), not a crash. */
fun missingField(): String = try {
    Json.decodeFromString(Stamp.serializer(), """{"label":"x"}""")
    "decoded"
} catch (e: SerializationException) {
    "SerializationException"
}

/** #110: kotlinx-datetime declared as 0.6.0 by the project, linked as the toolchain's 0.7.1 in hot reload builds. */
fun dateTag(): String = LocalDate(2024, 2, 29).toString()
EOF
sed -i '' 's|println("BOTAS ui=$UI_TAG $p domain=|println("BOTAS ts=${org.example.core.stampJson()} miss=${org.example.core.missingField()} date=${org.example.core.dateTag()} ui=$UI_TAG $p domain=|' \
  "$P/shared/src/commonMain/kotlin/org/example/kmphr/App.kt"
python3 "$HERE/accept-dup-91.py" "$P" --udid "$U" --rounds "$ROUNDS"
line=$(tr '\r' '\n' < "$P/shared/launch.log" | grep -m1 "BOTAS ts=" || true)
echo "first BOTAS line: ${line:0:160}"
case $line in
  *'ts={"at":"2023-11-14T22:13:20Z"'*'miss=SerializationException date=2024-02-29'*) echo "PASS #110: Instant encoded, missing field -> SerializationException, LocalDate";;
  *) echo "FAIL #110: expected ts={\"at\":\"2023-11-14T22:13:20Z\"...} miss=SerializationException date=2024-02-29"; exit 1;;
esac
