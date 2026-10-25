<#
  Builds and tests the face liveness modules with only a JDK (11+); no Maven needed.
    .\build.ps1             compile + run tests
    .\build.ps1 -SkipTests  compile only
    .\build.ps1 -Run        compile, test, then launch LIVE webcam face liveness
    .\build.ps1 -Run -Sim   compile, test, then launch the simulated desktop demo
    .\build.ps1 -Run -Device sbi:auto   live capture from the MOSIP SBI face device (found on ports 4501-4600)
    .\build.ps1 -Tool "interop --device webcam:0"   compile, then run a command-line tool (no tests)
  Maven users: mvn verify  (see pom.xml)
#>
param([switch]$Run, [switch]$Sim, [switch]$SkipTests, [string]$Tool, [string]$Device = 'webcam:0')
if ($Tool) { $SkipTests = $true }
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$out = Join-Path $root 'build'
$libDir = Join-Path $root '.lib'
$junitVersion = '1.10.2'
$junitJar = Join-Path $libDir "junit-platform-console-standalone-$junitVersion.jar"
$opencvJar = Join-Path $libDir 'opencv-4.9.0-0.jar'
$onnxJar = Join-Path $libDir 'onnxruntime-1.17.0.jar'
$modelDir = Join-Path $root 'models'
# YuNet face detector, MIT licence, OpenCV model zoo. Pinned by hash so a changed file is never used.
$yunetModel = Join-Path $modelDir 'face_detection_yunet_2023mar.onnx'
$yunetSha256 = '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'

function Download-IfMissing([string]$file, [string]$url, [string]$label) {
    if (-not (Test-Path $file)) {
        Write-Host "== Downloading $label"
        New-Item -ItemType Directory -Force (Split-Path $file) | Out-Null
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            # -f: fail on HTTP errors instead of saving the error page; -sS: no progress, errors still shown
            & curl.exe -fsSL -o $file $url
            if ($LASTEXITCODE -ne 0) {
                Remove-Item -ErrorAction SilentlyContinue $file
                throw "Download failed: $url"
            }
        } else {
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $file
        }
    }
}

function Compile([string]$srcDir, [string]$outDir, [string]$classpath) {
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $list = Join-Path $out ((Split-Path $outDir -Leaf) + '-sources.txt')
    Get-ChildItem -Recurse -Filter *.java $srcDir | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
        Set-Content -Encoding ascii $list
    $cpArgs = @()
    if ($classpath) { $cpArgs = @('-cp', $classpath) }
    & javac --release 11 -encoding UTF-8 -d $outDir @cpArgs "@$list"
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $srcDir" }
}

# Ensure libraries exist
Download-IfMissing $junitJar "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$junitVersion/junit-platform-console-standalone-$junitVersion.jar" "JUnit console launcher"
Download-IfMissing $opencvJar "https://repo1.maven.org/maven2/org/openpnp/opencv/4.9.0-0/opencv-4.9.0-0.jar" "OpenCV library"
Download-IfMissing $onnxJar "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.17.0/onnxruntime-1.17.0.jar" "ONNX Runtime"
Download-IfMissing $yunetModel "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx" "YuNet face detection model"
if ((Get-FileHash -Algorithm SHA256 $yunetModel).Hash -ne $yunetSha256) {
    Remove-Item $yunetModel
    throw "Checksum mismatch for $yunetModel; the file was deleted. Re-run to download it again."
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host '== Compiling liveness-core'
Compile "$root/liveness-core/src/main/java" "$out/core" ''
Copy-Item -Recurse -Force "$root/liveness-core/src/main/resources/*" "$out/core/"   # message bundles

Write-Host '== Compiling liveness-camera (OpenCV webcam)'
Compile "$root/liveness-camera/src/main/java" "$out/camera" "$out/core;$opencvJar"

Write-Host '== Compiling liveness-onnx-engine (Liveness/PAD engine)'
Compile "$root/liveness-onnx-engine/src/main/java" "$out/onnx" "$out/core;$opencvJar;$onnxJar"

Write-Host '== Compiling liveness-desktop-demo (simulation UI)'
Compile "$root/liveness-desktop-demo/src/main/java" "$out/demo" "$out/core"

Write-Host '== Compiling liveness-live-demo (live webcam UI)'
Compile "$root/liveness-live-demo/src/main/java" "$out/live" "$out/core;$out/camera;$out/onnx;$opencvJar;$onnxJar"

Write-Host '== Compiling liveness-tools (evaluation, interop, model packages)'
Compile "$root/liveness-tools/src/main/java" "$out/tools" "$out/core;$out/camera;$out/onnx;$opencvJar;$onnxJar"

if (-not $SkipTests) {
    Write-Host '== Compiling tests'
    Compile "$root/liveness-core/src/test/java" "$out/test" "$out/core;$junitJar"
    Compile "$root/liveness-onnx-engine/src/test/java" "$out/onnx-test" "$out/core;$out/onnx;$opencvJar;$onnxJar;$junitJar"
    Compile "$root/liveness-tools/src/test/java" "$out/tools-test" "$out/core;$out/camera;$out/tools;$opencvJar;$junitJar"
    Write-Host '== Running tests'
    & java -jar $junitJar execute --class-path "$out/core;$out/test;$out/onnx;$out/onnx-test;$out/camera;$out/tools;$out/tools-test;$opencvJar;$onnxJar" --scan-class-path --disable-banner --details=tree
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
}

if ($Tool) {
    & java -cp "$out/core;$out/camera;$out/onnx;$out/tools;$opencvJar;$onnxJar" io.mosip.registration.liveness.tools.LivenessTools ($Tool -split ' ')
    exit $LASTEXITCODE
}

if ($Run) {
    if ($Sim) {
        Write-Host '== Launching simulated demo (mock device)'
        & java -cp "$out/core;$out/demo" io.mosip.registration.liveness.demo.LivenessDemoApp
    } else {
        Write-Host '== Launching LIVE webcam capture and face liveness verification'
        & java "-Dmosip.liveness.device=$Device" -cp "$out/core;$out/camera;$out/onnx;$out/live;$opencvJar;$onnxJar" io.mosip.registration.liveness.live.LiveCaptureApp
    }
}
