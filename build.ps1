<#
  Builds and tests the face liveness modules with only a JDK (11+); no Maven needed.
    .\build.ps1             compile + run tests
    .\build.ps1 -SkipTests  compile only
    .\build.ps1 -Run        compile, test, then launch LIVE webcam face liveness
    .\build.ps1 -Run -Sim   compile, test, then launch the simulated desktop demo
  Maven users: mvn verify  (see pom.xml)
#>
param([switch]$Run, [switch]$Sim, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$out = Join-Path $root 'build'
$libDir = Join-Path $root '.lib'
$junitVersion = '1.10.2'
$junitJar = Join-Path $libDir "junit-platform-console-standalone-$junitVersion.jar"
$opencvJar = Join-Path $libDir 'opencv-4.9.0-0.jar'
$onnxJar = Join-Path $libDir 'onnxruntime-1.17.0.jar'

function Download-IfMissing([string]$file, [string]$url, [string]$label) {
    if (-not (Test-Path $file)) {
        Write-Host "== Downloading $label"
        New-Item -ItemType Directory -Force (Split-Path $file) | Out-Null
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            & curl.exe -L -o $file $url
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

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host '== Compiling liveness-core'
Compile "$root/liveness-core/src/main/java" "$out/core" ''

Write-Host '== Compiling liveness-camera (OpenCV webcam)'
Compile "$root/liveness-camera/src/main/java" "$out/camera" "$out/core;$opencvJar"

Write-Host '== Compiling liveness-onnx-engine (Liveness/PAD engine)'
Compile "$root/liveness-onnx-engine/src/main/java" "$out/onnx" "$out/core;$opencvJar;$onnxJar"

Write-Host '== Compiling liveness-desktop-demo (simulation UI)'
Compile "$root/liveness-desktop-demo/src/main/java" "$out/demo" "$out/core"

Write-Host '== Compiling liveness-live-demo (live webcam UI)'
Compile "$root/liveness-live-demo/src/main/java" "$out/live" "$out/core;$out/camera;$out/onnx;$opencvJar;$onnxJar"

if (-not $SkipTests) {
    Write-Host '== Compiling tests'
    Compile "$root/liveness-core/src/test/java" "$out/test" "$out/core;$junitJar"
    Write-Host '== Running tests'
    & java -jar $junitJar execute --class-path "$out/core;$out/test" --scan-class-path --disable-banner --details=tree
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
}

if ($Run) {
    if ($Sim) {
        Write-Host '== Launching simulated demo (mock device)'
        & java -cp "$out/core;$out/demo" io.mosip.registration.liveness.demo.LivenessDemoApp
    } else {
        Write-Host '== Launching LIVE webcam capture and face liveness verification'
        & java -cp "$out/core;$out/camera;$out/onnx;$out/live;$opencvJar;$onnxJar" io.mosip.registration.liveness.live.LiveCaptureApp
    }
}
