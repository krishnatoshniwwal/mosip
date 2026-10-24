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
# YuNet face detector, MIT licence, OpenCV model zoo. Pinned by hash so a changed file is never used.
YUNET_MODEL="$ROOT/models/face_detection_yunet_2023mar.onnx"
YUNET_SHA256=8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4

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
download_if_missing "$YUNET_MODEL" "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx" "YuNet face detection model"
if command -v sha256sum >/dev/null; then ACTUAL=$(sha256sum "$YUNET_MODEL" | cut -d' ' -f1); else ACTUAL=$(shasum -a 256 "$YUNET_MODEL" | cut -d' ' -f1); fi
if [ "$ACTUAL" != "$YUNET_SHA256" ]; then
  rm -f "$YUNET_MODEL"
  echo "Checksum mismatch for $YUNET_MODEL; the file was deleted. Re-run to download it again." >&2
  exit 1
fi

rm -rf "$OUT"; mkdir -p "$OUT"

echo "== Compiling liveness-core"
compile "$ROOT/liveness-core/src/main/java" "$OUT/core" ""
cp -R "$ROOT/liveness-core/src/main/resources/." "$OUT/core/"   # message bundles

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
  compile "$ROOT/liveness-onnx-engine/src/test/java" "$OUT/onnx-test" "$OUT/core$SEP$OUT/onnx$SEP$OPENCV_JAR$SEP$ONNX_JAR$SEP$JUNIT_JAR"
  echo "== Running tests"
  java -jar "$JUNIT_JAR" execute --class-path "$OUT/core$SEP$OUT/test$SEP$OUT/onnx$SEP$OUT/onnx-test$SEP$OPENCV_JAR$SEP$ONNX_JAR" --scan-class-path --disable-banner --details=tree
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
