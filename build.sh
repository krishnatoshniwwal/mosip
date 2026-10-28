#!/usr/bin/env bash
# Builds and tests the face liveness modules with only a JDK (11+); no Maven needed.
#   ./build.sh             compile + run tests
#   ./build.sh --skip-tests
#   ./build.sh --run       compile, test, then launch live webcam capture
#   ./build.sh --run --sim compile, test, then launch simulated mock demo
#   ./build.sh --run --device=sbi:auto   live capture from the MOSIP SBI face device
#   ./build.sh --run --regclient         Registration Client screens (login, resident face, supervisor) with liveness
#   ./build.sh --run --regclient --sim   the same with the mock device and a simulated person
#   ./build.sh tool evaluate|interop|models ...   compile, then run a command-line tool (no tests)
# Maven users: mvn verify  (see pom.xml)
set -euo pipefail

# pwd -W gives C:/... on Git Bash, which Windows javac and java can read; elsewhere plain pwd.
ROOT="$(cd "$(dirname "$0")" && { pwd -W 2>/dev/null || pwd; })"
OUT="$ROOT/build"
LIB_DIR="$ROOT/.lib"
JUNIT_VERSION=1.10.2
JUNIT_JAR="$LIB_DIR/junit-platform-console-standalone-$JUNIT_VERSION.jar"
OPENCV_JAR="$LIB_DIR/opencv-4.9.0-0.jar"
ONNX_JAR="$LIB_DIR/onnxruntime-1.17.0.jar"
# OpenJFX for the Registration Client UI (the client itself is JavaFX). Platform-specific jars carry the natives.
JAVAFX_VERSION=17.0.10
# YuNet face detector, MIT licence, OpenCV model zoo. Pinned by hash so a changed file is never used.
YUNET_MODEL="$ROOT/models/face_detection_yunet_2023mar.onnx"
YUNET_SHA256=8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
# MediaPipe Face Mesh (468 landmarks), Apache-2.0, run unchanged by OpenCV's TFLite reader: blink and smile.
MESH_MODEL="$ROOT/models/face_landmark.tflite"
MESH_SHA256=1055cb9d4a9ca8b8c688902a3a5194311138ba256bcc94e336d8373a5f30c814
# MediaPipe Iris (eyelid contour per eye), Apache-2.0: eye openness for blink.
IRIS_MODEL="$ROOT/models/iris_landmark.tflite"
IRIS_SHA256=d1744d2a09c25f501d39eba4faff47e53ecca8852c5ce19bce8eeac39357521f

SEP=':'
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';'; JAVAFX_PLATFORM=win ;;
  Darwin) JAVAFX_PLATFORM=mac; [ "$(uname -m)" = arm64 ] && JAVAFX_PLATFORM=mac-aarch64 ;;
  *) JAVAFX_PLATFORM=linux; [ "$(uname -m)" = aarch64 ] && JAVAFX_PLATFORM=linux-aarch64 ;;
esac
JAVAFX_CP=""
for m in base graphics controls; do
  JAVAFX_CP="$JAVAFX_CP${JAVAFX_CP:+$SEP}$LIB_DIR/javafx-$m-$JAVAFX_VERSION-$JAVAFX_PLATFORM.jar"
done

RUN=0; SIM=0; REGCLIENT=0; SKIP_TESTS=0; TOOL=0; TOOL_ARGS=(); DEVICE=webcam:0
if [ "${1:-}" = "tool" ]; then
  shift; TOOL=1; TOOL_ARGS=("$@"); set -- --skip-tests
fi
for arg in "$@"; do
  case "$arg" in
    --run) RUN=1 ;;
    --device=*) DEVICE="${arg#--device=}" ;;
    --sim) SIM=1 ;;
    --regclient) REGCLIENT=1 ;;
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
for m in base graphics controls; do
  download_if_missing "$LIB_DIR/javafx-$m-$JAVAFX_VERSION-$JAVAFX_PLATFORM.jar" \
    "https://repo1.maven.org/maven2/org/openjfx/javafx-$m/$JAVAFX_VERSION/javafx-$m-$JAVAFX_VERSION-$JAVAFX_PLATFORM.jar" "JavaFX $m"
done
download_if_missing "$YUNET_MODEL" "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx" "YuNet face detection model"
if command -v sha256sum >/dev/null; then ACTUAL=$(sha256sum "$YUNET_MODEL" | cut -d' ' -f1); else ACTUAL=$(shasum -a 256 "$YUNET_MODEL" | cut -d' ' -f1); fi
if [ "$ACTUAL" != "$YUNET_SHA256" ]; then
  rm -f "$YUNET_MODEL"
  echo "Checksum mismatch for $YUNET_MODEL; the file was deleted. Re-run to download it again." >&2
  exit 1
fi
download_if_missing "$MESH_MODEL" "https://storage.googleapis.com/mediapipe-assets/face_landmark.tflite" "MediaPipe face mesh model"
if command -v sha256sum >/dev/null; then ACTUAL=$(sha256sum "$MESH_MODEL" | cut -d' ' -f1); else ACTUAL=$(shasum -a 256 "$MESH_MODEL" | cut -d' ' -f1); fi
if [ "$ACTUAL" != "$MESH_SHA256" ]; then
  rm -f "$MESH_MODEL"
  echo "Checksum mismatch for $MESH_MODEL; the file was deleted. Re-run to download it again." >&2
  exit 1
fi
download_if_missing "$IRIS_MODEL" "https://storage.googleapis.com/mediapipe-assets/iris_landmark.tflite" "MediaPipe iris model"
if command -v sha256sum >/dev/null; then ACTUAL=$(sha256sum "$IRIS_MODEL" | cut -d' ' -f1); else ACTUAL=$(shasum -a 256 "$IRIS_MODEL" | cut -d' ' -f1); fi
if [ "$ACTUAL" != "$IRIS_SHA256" ]; then
  rm -f "$IRIS_MODEL"
  echo "Checksum mismatch for $IRIS_MODEL; the file was deleted. Re-run to download it again." >&2
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

echo "== Compiling liveness-tools (evaluation, interop, model packages)"
compile "$ROOT/liveness-tools/src/main/java" "$OUT/tools" "$OUT/core$SEP$OUT/camera$SEP$OUT/onnx$SEP$OPENCV_JAR$SEP$ONNX_JAR"

echo "== Compiling liveness-regclient (Registration Client integration, JavaFX)"
compile "$ROOT/liveness-regclient/src/main/java" "$OUT/regclient" "$OUT/core$SEP$OUT/camera$SEP$OUT/onnx$SEP$OPENCV_JAR$SEP$ONNX_JAR$SEP$JAVAFX_CP"

echo "== Compiling liveness-android (Android adapters: CameraX device, Flutter bridge; plain Java)"
compile "$ROOT/liveness-android/src/main/java" "$OUT/android" "$OUT/core"

if [ "$SKIP_TESTS" -eq 0 ]; then
  echo "== Compiling tests"
  compile "$ROOT/liveness-core/src/test/java" "$OUT/test" "$OUT/core$SEP$JUNIT_JAR"
  compile "$ROOT/liveness-onnx-engine/src/test/java" "$OUT/onnx-test" "$OUT/core$SEP$OUT/onnx$SEP$OPENCV_JAR$SEP$ONNX_JAR$SEP$JUNIT_JAR"
  compile "$ROOT/liveness-camera/src/test/java" "$OUT/camera-test" "$OUT/core$SEP$OUT/camera$SEP$OPENCV_JAR$SEP$JUNIT_JAR"
  compile "$ROOT/liveness-tools/src/test/java" "$OUT/tools-test" "$OUT/core$SEP$OUT/camera$SEP$OUT/tools$SEP$OPENCV_JAR$SEP$JUNIT_JAR"
  compile "$ROOT/liveness-regclient/src/test/java" "$OUT/regclient-test" "$OUT/core$SEP$OUT/regclient$SEP$JUNIT_JAR"
  compile "$ROOT/liveness-android/src/test/java" "$OUT/android-test" "$OUT/core$SEP$OUT/android$SEP$JUNIT_JAR"
  echo "== Running tests"
  java -jar "$JUNIT_JAR" execute --class-path "$OUT/core$SEP$OUT/test$SEP$OUT/onnx$SEP$OUT/onnx-test$SEP$OUT/camera$SEP$OUT/camera-test$SEP$OUT/tools$SEP$OUT/tools-test$SEP$OUT/regclient$SEP$OUT/regclient-test$SEP$OUT/android$SEP$OUT/android-test$SEP$OPENCV_JAR$SEP$ONNX_JAR" --scan-class-path --disable-banner --details=tree
fi

if [ "$RUN" -eq 1 ]; then
  if [ "$REGCLIENT" -eq 1 ]; then
    echo "== Launching Registration Client screens with face liveness"
    SIM_FLAG=false; [ "$SIM" -eq 1 ] && SIM_FLAG=true
    java "-Dmosip.liveness.device=$DEVICE" "-Dmosip.liveness.sim=$SIM_FLAG" \
      -cp "$OUT/core$SEP$OUT/camera$SEP$OUT/onnx$SEP$OUT/regclient$SEP$OPENCV_JAR$SEP$ONNX_JAR$SEP$JAVAFX_CP" \
      io.mosip.registration.liveness.regclient.demo.RegClientDemo
  elif [ "$SIM" -eq 1 ]; then
    echo "== Launching simulated demo (mock device)"
    java -cp "$OUT/core$SEP$OUT/demo" io.mosip.registration.liveness.demo.LivenessDemoApp
  else
    echo "== Launching LIVE webcam capture and face liveness verification"
    java "-Dmosip.liveness.device=$DEVICE" -cp "$OUT/core$SEP$OUT/camera$SEP$OUT/onnx$SEP$OUT/live$SEP$OPENCV_JAR$SEP$ONNX_JAR" io.mosip.registration.liveness.live.LiveCaptureApp
  fi
fi

if [ "$TOOL" -eq 1 ]; then
  java -cp "$OUT/core$SEP$OUT/camera$SEP$OUT/onnx$SEP$OUT/tools$SEP$OPENCV_JAR$SEP$ONNX_JAR" \
    io.mosip.registration.liveness.tools.LivenessTools ${TOOL_ARGS[@]+"${TOOL_ARGS[@]}"}
fi
