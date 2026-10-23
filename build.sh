#!/usr/bin/env bash
# Builds and tests the face liveness modules with only a JDK (11+); no Maven needed.
#   ./build.sh             compile + run tests
#   ./build.sh --skip-tests
#   ./build.sh --run       compile, test, then launch the desktop demo
# Maven users: mvn verify  (see pom.xml)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
JUNIT_VERSION=1.10.2
JUNIT_JAR="$ROOT/.lib/junit-platform-console-standalone-$JUNIT_VERSION.jar"
SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac

RUN=0; SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --run) RUN=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

compile() { # src out classpath
  mkdir -p "$2"
  find "$1" -name '*.java' > "$2.sources"
  javac --release 11 -encoding UTF-8 -d "$2" ${3:+-cp "$3"} "@$2.sources"
}

rm -rf "$OUT"; mkdir -p "$OUT"
echo "== Compiling liveness-core"
compile "$ROOT/liveness-core/src/main/java" "$OUT/core" ""
echo "== Compiling liveness-desktop-demo"
compile "$ROOT/liveness-desktop-demo/src/main/java" "$OUT/demo" "$OUT/core"

if [ "$SKIP_TESTS" -eq 0 ]; then
  if [ ! -f "$JUNIT_JAR" ]; then
    echo "== Downloading JUnit console launcher $JUNIT_VERSION"
    mkdir -p "$(dirname "$JUNIT_JAR")"
    curl -fsSL -o "$JUNIT_JAR" \
      "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"
  fi
  echo "== Compiling tests"
  compile "$ROOT/liveness-core/src/test/java" "$OUT/test" "$OUT/core$SEP$JUNIT_JAR"
  echo "== Running tests"
  java -jar "$JUNIT_JAR" execute --class-path "$OUT/core$SEP$OUT/test" --scan-class-path --disable-banner --details=tree
fi

if [ "$RUN" -eq 1 ]; then
  echo "== Launching demo"
  java -cp "$OUT/core$SEP$OUT/demo" io.mosip.registration.liveness.demo.LivenessDemoApp
fi
