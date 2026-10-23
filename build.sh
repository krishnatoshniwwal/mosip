#!/usr/bin/env bash
# Builds and tests the face liveness modules with only a JDK (11+); no Maven needed.
#   ./build.sh             compile + run tests
#   ./build.sh --skip-tests
#   ./build.sh --run       compile, test, then launch live webcam capture
#   ./build.sh --run --sim compile, test, then launch simulated mock demo
# Maven users: mvn verify  (see pom.xml)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
LIB_DIR="$ROOT/.lib"
JUNIT_VERSION=1.10.2
JUNIT_JAR="$LIB_DIR/junit-platform-console-standalone-$JUNIT_VERSION.jar"
OPENCV_JAR="$LIB_DIR/opencv-4.9.0-0.jar"
ONNX_JAR="$LIB_DIR/onnxruntime-1.17.0.jar"

SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac

RUN=0; SIM=0; SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --run) RUN=1 ;;
    --sim) SIM=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

download_if_missing() {
  if [ ! -f "$1" ]; then
    echo "== Downloading $3"
    mkdir -p "$(dirname "$1")"
    curl -fsSL -o "$1" "$2"
  fi
}

compile() { # src out classpath
  mkdir -p "$2"
  find "$1" -name '*.java' > "$2.sources"
  javac --release 11 -encoding UTF-8 -d "$2" ${3:+-cp "$3"} "@$2.sources"
}

mkdir -p "$LIB_DIR"
download_if_missing "$JUNIT_JAR" "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar" "JUnit console launcher"
download_if_missing "$OPENCV_JAR" "https://repo1.maven.org/maven2/org/openpnp/opencv/4.9.0-0/opencv-4.9.0-0.jar" "OpenCV library"
download_if_missing "$ONNX_JAR" "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.17.0/onnxruntime-1.17.0.jar" "ONNX Runtime"

rm -rf "$OUT"; mkdir -p "$OUT"

echo "== Compiling liveness-core"
compile "$ROOT/liveness-core/src/main/java" "$OUT/core" ""

echo "== Compiling liveness-camera (OpenCV webcam)"
compile "$ROOT/liveness-camera/src/main/java" "$OUT/camera" "$OUT/core$SEP$OPENCV_JAR"

echo "== Compiling liveness-onnx-engine (Liveness/PAD engine)"
compile "$ROOT/liveness-onnx-engine/src/main/java" "$OUT/onnx" "$OUT/core$SEP$OPENCV_JAR$SEP$ONNX_JAR"

echo "== Compiling liveness-desktop-demo (simulation UI)"
compile "$ROOT/liveness-desktop-demo/src/main/java" "$OUT/demo" "$OUT/core"

echo "== Compiling liveness-live-demo (live webcam UI)"
compile "$ROOT/liveness-live-demo/src/main/java" "$OUT/live" "$OUT/core$SEP$OUT/camera$SEP$OUT/onnx$SEP$OPENCV_JAR$SEP$ONNX_JAR"

if [ "$SKIP_TESTS" -eq 0 ]; then
  echo "== Compiling tests"
  compile "$ROOT/liveness-core/src/test/java" "$OUT/test" "$OUT/core$SEP$JUNIT_JAR"
  echo "== Running tests"
  java -jar "$JUNIT_JAR" execute --class-path "$OUT/core$SEP$OUT/test" --scan-class-path --disable-banner --details=tree
fi

if [ "$RUN" -eq 1 ]; then
  if [ "$SIM" -eq 1 ]; then
    echo "== Launching simulated demo (mock device)"
    java -cp "$OUT/core$SEP$OUT/demo" io.mosip.registration.liveness.demo.LivenessDemoApp
  else
    echo "== Launching LIVE webcam capture and face liveness verification"
    java -cp "$OUT/core$SEP$OUT/camera$SEP$OUT/onnx$SEP$OUT/live$SEP$OPENCV_JAR$SEP$ONNX_JAR" io.mosip.registration.liveness.live.LiveCaptureApp
  fi
fi
