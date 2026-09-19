# ---------------------------------------------------------------------------------------
# One command, clean build -> Setup.exe. Run fetch-dependencies.ps1 once first (stages
# third-party\pgsql and third-party\vc_redist.x64.exe -- not rebuilt here since they
# don't change between app releases).
#
#   cd installer
#   .\fetch-dependencies.ps1      # once
#   .\build-installer.ps1         # every release
#
# Produces installer\out\RoyalPizzaSahowala-Setup-<version>.exe
# ---------------------------------------------------------------------------------------

$ErrorActionPreference = "Stop"
$Root       = Split-Path $PSScriptRoot -Parent   # repo root, one level up from installer\
$Installer  = $PSScriptRoot
$Build      = Join-Path $Installer "build"
$JdkHome    = "C:\Program Files\Java\jdk-25.0.4.1"
$Jlink      = Join-Path $JdkHome "bin\jlink.exe"
$Jpackage   = Join-Path $JdkHome "bin\jpackage.exe"
$Iscc       = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

foreach ($required in @(
    (Join-Path $Installer "third-party\pgsql\bin\postgres.exe"),
    (Join-Path $Installer "third-party\vc_redist.x64.exe")
)) {
    if (-not (Test-Path $required)) {
        throw "Missing $required -- run fetch-dependencies.ps1 first."
    }
}
if (-not (Test-Path $Iscc)) { throw "Inno Setup 6 (ISCC.exe) not found at $Iscc." }

# --- 1. Compile + jar ---------------------------------------------------------------
Step "Compiling and building the application jar"
Push-Location $Root
try {
    & "$Root\build.ps1" -Jar
    if ($LASTEXITCODE -ne 0) { throw "build.ps1 -Jar failed." }
} finally {
    Pop-Location
}

Remove-Item $Build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$Build\app\lib" | Out-Null
Move-Item "$Root\RoyalPizzaSahowala.jar" "$Build\app\RoyalPizzaSahowala.jar" -Force
Copy-Item "$Root\lib\flatlaf-3.7.2.jar" "$Build\app\lib\" -Force
Copy-Item "$Root\lib\postgresql-42.7.3.jar" "$Build\app\lib\" -Force

# --- 2. jlink runtime ----------------------------------------------------------------
Step "Building the trimmed JRE (jlink)"
$RuntimeDir = Join-Path $Build "runtime"
Remove-Item $RuntimeDir -Recurse -Force -ErrorAction SilentlyContinue
# This exact module list was arrived at empirically (not from jdeps alone), by running
# Bootstrap against smaller sets and adding whatever ClassNotFoundException named next:
# java.management (PgJDBC's use of ManagementFactory) and java.security.sasl
# (SCRAM-SHA-256 auth) both showed up only at runtime, and jdk.crypto.ec proved
# unnecessary despite being a natural guess for TLS. Don't trim this without re-running
# that empirical check -- jdeps alone under-reports it.
& $Jlink --module-path "$JdkHome\jmods" `
    --add-modules java.base,java.desktop,java.net.http,java.sql,java.management,java.logging,java.security.sasl `
    --output $RuntimeDir `
    --strip-debug --no-header-files --no-man-pages --compress=zip-6
if ($LASTEXITCODE -ne 0) { throw "jlink failed." }

# --- 3. jpackage app-image -------------------------------------------------------------
Step "Building the app-image launcher (jpackage)"
$JpackageOut = Join-Path $Build "jpackage-out"
Remove-Item $JpackageOut -Recurse -Force -ErrorAction SilentlyContinue
$AppVersion = "1.0.0"

# --java-options splits its value on whitespace by design (it lets one invocation carry
# several JVM args), which breaks a path containing a literal space ("Royal Pizza
# Sahowala"). Wrapping it in an escaped inner "..." pair works when jpackage is invoked
# directly from bash -- confirmed earlier -- but does NOT survive PowerShell's own
# native-argv marshalling: running this exact script re-introduced the identical bug
# (RoyalPizzaSahowala.cfg came out with the path split across four garbage java-options
# lines, and the installed app failed to start with "db.properties not found"). Passing
# the space-free option here and appending the two space-bearing ones directly into the
# generated .cfg file below sidesteps process-argv escaping entirely -- it's plain text
# file I/O, so no shell's quoting rules can mangle it.
& $Jpackage `
    --type app-image `
    --name RoyalPizzaSahowala `
    --vendor "Royal Pizza Sahowala" `
    --app-version $AppVersion `
    --input "$Build\app" `
    --main-jar RoyalPizzaSahowala.jar `
    --main-class rps.app.App `
    --runtime-image $RuntimeDir `
    --dest $JpackageOut `
    --icon "$Installer\assets\rps-icon.ico" `
    --java-options "--enable-native-access=ALL-UNNAMED"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

$cfgPath = Join-Path $JpackageOut "RoyalPizzaSahowala\app\RoyalPizzaSahowala.cfg"
Add-Content -Path $cfgPath -Value 'java-options=-Drps.config=C:\ProgramData\Royal Pizza Sahowala\db.properties'
Add-Content -Path $cfgPath -Value 'java-options=-Drps.pgsql.home=C:\Program Files\Royal Pizza Sahowala\pgsql'

# Verify rather than trust: split lines are exactly what caused the original bug, so
# confirm each option landed as ONE line before the installer packages this app-image up.
$cfgLines = Get-Content $cfgPath
$hasConfig = $cfgLines -contains 'java-options=-Drps.config=C:\ProgramData\Royal Pizza Sahowala\db.properties'
$hasPgHome = $cfgLines -contains 'java-options=-Drps.pgsql.home=C:\Program Files\Royal Pizza Sahowala\pgsql'
if (-not ($hasConfig -and $hasPgHome)) {
    throw "RoyalPizzaSahowala.cfg does not contain the expected single-line java-options -- check $cfgPath by hand before proceeding."
}

# --- 4. Compile the installer ----------------------------------------------------------
Step "Compiling the installer (Inno Setup)"
New-Item -ItemType Directory -Force -Path (Join-Path $Installer "out") | Out-Null
# royalpizza.iss hardcodes #define AppVersion itself -- keep the two in sync by hand
# when bumping a release; passing /D here would just conflict with that #define.
& $Iscc (Join-Path $Installer "royalpizza.iss")
if ($LASTEXITCODE -ne 0) { throw "ISCC failed." }

Step "Done"
Get-ChildItem (Join-Path $Installer "out\*.exe") | ForEach-Object {
    Write-Host ("  {0}  ({1:N1} MB)" -f $_.Name, ($_.Length / 1MB)) -ForegroundColor Green
}
