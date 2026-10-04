#!/usr/bin/env bash
# Compiles the demonstration plugin to DEX and prints it as base64, ready to
# paste into a plugin_load call.
#
# Plugins link against dev.posedmcp.plugin.* from the PosEdMCP APK. At runtime
# the injected DEX is loaded with the module's class loader as its parent, so
# those interfaces resolve without the plugin bundling them.
#
# The JDK and build-tools here are Windows binaries, so paths are converted with
# cygpath and the classpath separator is ';' - passing Unix paths through makes
# javac report "file not found" for every source.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${REPO_ROOT}/tools/plugin-demo/src"
OUT_DIR="${REPO_ROOT}/tools/plugin-demo/build"

ANDROID_JAR="${ANDROID_JAR:-E:/SDK/platforms/android-36.1/android.jar}"
D8="${D8:-E:/SDK/build-tools/36.1.0/d8.bat}"
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-22}"
JAVAC="${JAVAC:-${JAVA_HOME}/bin/javac}"

# Where AGP left the app's compiled classes.
APP_CLASSES="${REPO_ROOT}/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
if [[ ! -d "${APP_CLASSES}" ]]; then
    APP_CLASSES="${REPO_ROOT}/app/build/intermediates/javac/debug/classes"
fi
if [[ ! -d "${APP_CLASSES}" ]]; then
    echo "App classes not found. Run ./tools/gradle.sh assembleDebug first." >&2
    exit 1
fi
if [[ ! -f "${ANDROID_JAR}" ]]; then
    echo "android.jar not found at ${ANDROID_JAR}" >&2
    exit 1
fi

# Convert to the form the Windows tools expect.
win() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac

WIN_SRC="$(win "${SRC_DIR}")"
WIN_OUT="$(win "${OUT_DIR}")"
WIN_JAR="$(win "${ANDROID_JAR}")"
WIN_APP_CLASSES="$(win "${APP_CLASSES}")"
WIN_JAVAC="$(win "${JAVAC}")"

# Run D8 through its jar rather than d8.bat: the batch wrapper does not forward
# arguments reliably when launched from MSYS.
D8_JAR="${D8_JAR:-$(dirname "${ANDROID_JAR}")/../build-tools/36.1.0/lib/d8.jar}"
if [[ ! -f "${D8_JAR}" ]]; then
    D8_JAR="E:/SDK/build-tools/36.1.0/lib/d8.jar"
fi
WIN_D8_JAR="$(win "${D8_JAR}")"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/classes"

mapfile -t SOURCES < <(find "${WIN_SRC}" -name '*.java' | while read -r f; do win "$f"; done)
echo "compiling ${#SOURCES[@]} source file(s)"

# MSYS must not rewrite the ';' classpath or the backslash paths.
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "${WIN_JAVAC}" \
    -source 17 -target 17 -nowarn -encoding UTF-8 \
    -classpath "${WIN_JAR}${SEP}${WIN_APP_CLASSES}" \
    -d "${WIN_OUT}\\classes" "${SOURCES[@]}"

echo "converting to DEX"
mapfile -t CLASSES < <(find "${OUT_DIR}/classes" -name '*.class')
WIN_CLASSES=()
for c in "${CLASSES[@]}"; do WIN_CLASSES+=("$(win "$c")"); done

MSYS_NO_PATHCONV=1 java -cp "${WIN_D8_JAR}" com.android.tools.r8.D8 \
    --min-api 31 --output "${WIN_OUT}" \
    --lib "${WIN_JAR}" \
    "${WIN_CLASSES[@]}"

echo
echo "DEX: ${OUT_DIR}/classes.dex ($(stat -c %s "${OUT_DIR}/classes.dex") bytes)"
base64 -w0 "${OUT_DIR}/classes.dex" > "${OUT_DIR}/plugin.b64"
echo "base64 written to ${OUT_DIR}/plugin.b64"
head -c 100 "${OUT_DIR}/plugin.b64"
echo "..."
