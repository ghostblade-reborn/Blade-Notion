#!/usr/bin/env bash
# GhostBlade Notion — build pipeline (no Gradle).
# aapt2 (resources+manifest) -> javac (R.java) -> kotlinc (app) ->
# d8 (app classes + kotlin-stdlib) -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/tools/android-sdk"
BT="$SDK/build-tools/35.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
KOTLINC="$HOME/workspace/tools/kotlinc/bin/kotlinc"
STDLIB="$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar"
KEYSTORE="$HOME/workspace/projects/ghostblade-task-android/debug.keystore"

B=build
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "== aapt2: compile + link resources =="
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$AJAR" \
  --manifest AndroidManifest.xml -R "$B/res.zip" \
  --java "$B/gen" --auto-add-overlay

echo "== javac: R.java =="
javac --release 8 -cp "$AJAR" -d "$B/classes" $(find "$B/gen" -name '*.java')

echo "== kotlinc: app sources =="
"$KOTLINC" src/com/ghostblade/notion/*.kt -cp "$AJAR:$B/classes" -d "$B/classes"

echo "== d8: dex (app + kotlin-stdlib) =="
(cd "$B/classes" && jar cf ../app.jar .)
"$BT/d8" --release --min-api 24 --lib "$AJAR" \
  --output "$B/dex" "$B/app.jar" "$STDLIB"

echo "== package + align + sign =="
# Reproducibility: pin the classes.dex zip entry to the zip epoch
# (1980-01-01, matching aapt2's deterministic entry stamps) and strip
# extra fields, so the entry carries no build-time timestamp.
(cd "$B/dex" && touch -d '1980-01-01 00:00:00 UTC' classes.dex && zip -X -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
# Versioned output name so downloads never collide into "(1)", "(2)" duplicates.
VER=$(grep -o 'android:versionName="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
OUT="GhostBladeNotion-v${VER}.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$B/aligned.apk"
cp "$OUT" GhostBladeNotion.apk

"$BT/apksigner" verify "$OUT"
# `|| true`: with pipefail, head closing the pipe early can SIGPIPE
# aapt2 and fail an otherwise good build (observed exit 141 in v0.2).
"$BT/aapt2" dump badging "$OUT" | head -8 || true
ls -la "$OUT"
sha256sum "$OUT"
