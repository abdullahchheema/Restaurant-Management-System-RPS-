# Build succeeded == RoyalPizzaSahowala.jar exists and run.ps1's classpath (bin;lib/*)
# still works unchanged, so this is additive, not a replacement of the existing dev
# loop. The jar is what installer/build-installer.ps1 feeds to jpackage — jpackage
# builds an app image from a jar + its classpath jars, not from loose .class files.
param(
    [switch]$Jar
)

$ErrorActionPreference = "Stop"
$jdkBin = "C:\Program Files\Java\jdk-25.0.4.1\bin"
$javac = "javac"
$jarExe = "jar"
if (Test-Path "$jdkBin\javac.exe") {
    $javac = "$jdkBin\javac.exe"
    $jarExe = "$jdkBin\jar.exe"
}

New-Item -ItemType Directory -Force -Path bin | Out-Null

$sources = Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sourcesFile = New-TemporaryFile
$sources | Set-Content -Path $sourcesFile

# -encoding is pinned, not left to the platform default: the UI and receipts contain
# characters like — · … × that would compile to mojibake under a Windows-1252 default.
& $javac -encoding UTF-8 -d bin -cp "lib/*" "@$sourcesFile"
$exitCode = $LASTEXITCODE

Remove-Item $sourcesFile -Force

if ($exitCode -ne 0) {
    Write-Host "Build failed." -ForegroundColor Red
    exit $exitCode
}
Write-Host "Build succeeded." -ForegroundColor Green

# BrandLogo looks up images/rps-logo.png as a classpath resource -- copying it into bin/
# puts it on the classpath for both run.ps1 (bin;lib/*) and the packaged jar below (which
# is built from bin/'s contents), with no separate packaging step to keep in sync.
if (Test-Path images) {
    New-Item -ItemType Directory -Force -Path bin\images | Out-Null
    Copy-Item images\* bin\images\ -Recurse -Force
}

if ($Jar) {
    # Main-Class lets the jar be double-clicked/run directly (java -jar) as a fallback to
    # the jpackage launcher; the app also needs postgresql-42.7.3.jar and flatlaf-3.7.2.jar
    # on the classpath alongside it, same as run.ps1's "bin;lib/*" today.
    $manifest = New-TemporaryFile
    "Main-Class: rps.app.App" | Set-Content -Path $manifest -Encoding ASCII
    & $jarExe --create --file "RoyalPizzaSahowala.jar" --manifest $manifest -C bin .
    $jarExit = $LASTEXITCODE
    Remove-Item $manifest -Force
    if ($jarExit -eq 0) {
        Write-Host "Jar built: RoyalPizzaSahowala.jar" -ForegroundColor Green
    } else {
        Write-Host "Jar build failed." -ForegroundColor Red
        exit $jarExit
    }
}

exit 0
