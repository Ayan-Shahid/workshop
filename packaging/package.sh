#!/usr/bin/env bash
# Builds the downloadable packages and the update files for one version.
#
#   packaging/package.sh 1.2.0                 # everything goes to build/release/
#   UPDATE_FEED=https://.../latest.json packaging/package.sh 1.2.0
#
# Produces in build/release/:
#   WorkshopManager-Windows-<v>.zip   full package (Java included) for a new Windows PC
#   WorkshopManager-Mac-<v>.zip       full package (Java included) for a new Mac
#   updates/*.jar                     the program's jars; installed copies download only the ones they lack
#   latest.json                       signed list of the jars per OS (only when UPDATE_SIGNING_KEY and RELEASE_BASE are set;
#                                     RELEASE_BASE is the URL the updates/ files will be downloadable from)
#
# Runs on Linux or macOS with JDK 21+, curl, unzip and zip. The release workflow runs it on GitHub.
set -euo pipefail
V="${1:?usage: packaging/package.sh <version>}"; V="${V#v}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
OUT="$ROOT/build/release"; WORK="$ROOT/build/pkg"; CACHE="$ROOT/build/pkgcache"
FEED="${UPDATE_FEED:-}"
JRE="21.0.12+8"; JRE_U="${JRE/+/_}"; JRE_BASE="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${JRE/+/%2B}"
rm -rf "$OUT" "$WORK"; mkdir -p "$OUT" "$WORK" "$CACHE"

fetch() { # fetch <file> : downloads a Temurin JRE once and checks it against the published SHA-256
  local f="$1"
  if [ ! -f "$CACHE/$f" ]; then curl -fsSL -o "$CACHE/$f.part" "$JRE_BASE/$f"; mv "$CACHE/$f.part" "$CACHE/$f"; fi
  local want; want="$(curl -fsSL "$JRE_BASE/$f.sha256.txt" | cut -d' ' -f1)"
  local got; got="$( (sha256sum "$CACHE/$f" 2>/dev/null || shasum -a 256 "$CACHE/$f") | cut -d' ' -f1)"
  [ "$want" = "$got" ] || { echo "Checksum mismatch for $f" >&2; rm -f "$CACHE/$f"; exit 1; }
}

stage() { # stage <os>: the app as separate jars in build/stage/<os>
  ./gradlew -q -PappVersion="$V" -PupdateFeed="$FEED" -PtargetOs="$1" stageApp
  mkdir -p "$OUT/updates"; cp -n "build/stage/$1/lib/"*.jar "$OUT/updates/" 2>/dev/null || true
}
install_app() { # install_app <os> <app folder>
  mkdir -p "$2/lib"; cp "$LAUNCHER" "$2/"; cp "build/stage/$1/lib/"*.jar "$2/lib/"; cp "build/stage/$1/classpath.txt" "$2/current.txt"
}

echo "== Building app jars $V (feed: ${FEED:-none})"
./gradlew -q -PappVersion="$V" launcherJar fetchLaunch4j
LAUNCHER="build/launcher/WorkshopLauncher.jar"
stage windows
stage mac
NUMV="${V%%-*}"

# ---------------- Windows ----------------
echo "== Windows package"
W="$WORK/win/Workshop Manager"; mkdir -p "$W/app"
fetch "OpenJDK21U-jre_x64_windows_hotspot_$JRE_U.zip"
unzip -q "$CACHE/OpenJDK21U-jre_x64_windows_hotspot_$JRE_U.zip" -d "$WORK/win/jre"
mv "$WORK/win/jre/"*/ "$W/runtime"
install_app windows "$W/app"
L4J="$WORK/launch4j"; mkdir -p "$L4J/lib"
cp build/launch4j-jars/*.jar "$L4J/lib/"; mv "$L4J/lib/launch4j-3.50.jar" "$L4J/launch4j.jar"
(cd "$L4J" && unzip -q -o lib/launch4j-3.50-workdir-linux64.jar -d wd && rm -f lib/launch4j-3.50-workdir-linux64.jar \
  && cp -r wd/launch4j-3.50-workdir-linux64/. . && rm -rf wd && chmod +x bin/*)
exe() { # exe <name> <mutex> <extra jvm options>
  cat > "$WORK/l4j.xml" <<XML
<launch4jConfig>
  <dontWrapJar>true</dontWrapJar><headerType>gui</headerType>
  <jar>app\\WorkshopLauncher.jar</jar>
  <outfile>$W/$1.exe</outfile>
  <errTitle>Workshop Manager</errTitle><chdir>.</chdir><priority>normal</priority>
  <stayAlive>false</stayAlive><restartOnCrash>false</restartOnCrash>
  <icon>$ROOT/packaging/icon.ico</icon>
  <singleInstance><mutexName>$2</mutexName><windowTitle>Workshop Manager</windowTitle></singleInstance>
  <jre>
    <path>runtime</path><requiresJdk>false</requiresJdk><requires64Bit>true</requires64Bit>
    <initialHeapSize>64</initialHeapSize><maxHeapSize>1024</maxHeapSize>
    <opt>-Dfile.encoding=UTF-8</opt>$3
  </jre>
  <versionInfo>
    <fileVersion>$NUMV.0</fileVersion><txtFileVersion>$V</txtFileVersion>
    <fileDescription>Workshop Manager</fileDescription><copyright>Workshop Manager</copyright>
    <productVersion>$NUMV.0</productVersion><txtProductVersion>$V</txtProductVersion>
    <productName>Workshop Manager</productName><companyName>Workshop Manager</companyName>
    <internalName>WorkshopManager</internalName><originalFilename>$1.exe</originalFilename>
    <language>ENGLISH_US</language>
  </versionInfo>
</launch4jConfig>
XML
  java -jar "$L4J/launch4j.jar" "$WORK/l4j.xml" >/dev/null
}
exe "Workshop Manager" WorkshopManagerMain ""
exe "Workshop Manager - Sample data" WorkshopManagerSample \
  '<opt>-Dworkshop.demo=1</opt><opt>-Dworkshop.dataDir="%APPDATA%\WorkshopManagerSample\data"</opt><opt>-Duser.home="%APPDATA%\WorkshopManagerSample"</opt>'
for f in "READ ME FIRST.txt" "Start Workshop Manager (backup way).bat"; do perl -pe 's/\r?\n/\r\n/' "packaging/windows/$f" > "$W/$f"; done
(cd "$WORK/win" && zip -qr "$OUT/WorkshopManager-Windows-$V.zip" "Workshop Manager")

# ---------------- Mac ----------------
echo "== Mac package"
M="$WORK/mac/Workshop Manager (Mac)"; mkdir -p "$M"
bundle() { # bundle <app name> <exec> <id>
  local A="$M/$1.app/Contents"; mkdir -p "$A/MacOS" "$A/Resources"
  sed -e "s/@NAME@/$1/g" -e "s/@EXEC@/$2/g" -e "s/@ID@/$3/g" -e "s/@VERSION@/$NUMV/g" packaging/mac/Info.plist > "$A/Info.plist"
  cp packaging/icon.icns "$A/Resources/icon.icns"
  cp "packaging/mac/$2" "$A/MacOS/$2"; chmod 755 "$A/MacOS/$2"
}
bundle "Workshop Manager" WorkshopManager pk.workshop.manager
bundle "Workshop Manager - Sample data" SampleData pk.workshop.manager.sample
A="$M/Workshop Manager.app/Contents"; mkdir -p "$A/runtime"
install_app mac "$A/app"
for arch in aarch64:arm64 x64:x64; do
  f="OpenJDK21U-jre_${arch%%:*}_mac_hotspot_$JRE_U.tar.gz"; fetch "$f"
  mkdir -p "$WORK/mac/$arch"; tar -xzf "$CACHE/$f" -C "$WORK/mac/$arch"
  mv "$WORK/mac/$arch/"*/ "$A/runtime/${arch##*:}"
done
cp "packaging/mac/READ ME FIRST.txt" "$M/"
(cd "$WORK/mac" && zip -qry "$OUT/WorkshopManager-Mac-$V.zip" "Workshop Manager (Mac)")

if [ -n "${UPDATE_SIGNING_KEY:-}" ] && [ -n "${RELEASE_BASE:-}" ]; then
  echo "== Signing latest.json"
  java packaging/UpdateKeys.java feed "$V" "$RELEASE_BASE" "${RELEASE_NOTES:-/dev/null}" windows=build/stage/windows mac=build/stage/mac > "$OUT/latest.json"
fi
echo "== Done"; ls -lh "$OUT"; echo "$(ls "$OUT/updates" | wc -l) update jars, $(du -sh "$OUT/updates" | cut -f1)"
