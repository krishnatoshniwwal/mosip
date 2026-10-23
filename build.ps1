<#
  Builds and tests the face liveness modules with only a JDK (11+); no Maven needed.
    .\build.ps1             compile + run tests
    .\build.ps1 -SkipTests  compile only
    .\build.ps1 -Run        compile, test, then launch the desktop demo
  Maven users: mvn verify  (see pom.xml)
#>
param([switch]$Run, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$out = Join-Path $root 'build'
$junitVersion = '1.10.2'
$junitJar = Join-Path $root ".lib/junit-platform-console-standalone-$junitVersion.jar"

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

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host '== Compiling liveness-core'
Compile "$root/liveness-core/src/main/java" "$out/core" ''

Write-Host '== Compiling liveness-desktop-demo'
Compile "$root/liveness-desktop-demo/src/main/java" "$out/demo" "$out/core"

if (-not $SkipTests) {
    if (-not (Test-Path $junitJar)) {
        Write-Host "== Downloading JUnit console launcher $junitVersion"
        New-Item -ItemType Directory -Force (Split-Path $junitJar) | Out-Null
        $url = "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$junitVersion/junit-platform-console-standalone-$junitVersion.jar"
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $junitJar
    }
    Write-Host '== Compiling tests'
    Compile "$root/liveness-core/src/test/java" "$out/test" "$out/core;$junitJar"
    Write-Host '== Running tests'
    & java -jar $junitJar execute --class-path "$out/core;$out/test" --scan-class-path --disable-banner --details=tree
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
}

if ($Run) {
    Write-Host '== Launching demo'
    & java -cp "$out/core;$out/demo" io.mosip.registration.liveness.demo.LivenessDemoApp
}
