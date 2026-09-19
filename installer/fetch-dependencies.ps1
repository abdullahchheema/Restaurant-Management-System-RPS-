# ---------------------------------------------------------------------------------------
# Stages the two third-party payloads the installer bundles: the PostgreSQL 17 server
# binaries and the Visual C++ redistributable Postgres links against. Run this ONCE on
# a dev machine before build-installer.ps1; the result lands in installer\third-party\,
# which is gitignored (these are large, and the .iss script has no need to see them
# through source control -- only the fact that this script can reproduce them matters).
#
# PostgreSQL: copied from an already-installed local PostgreSQL 17, not downloaded fresh.
# That copy is the exact same binary this project's QA suites have been running against
# all session -- reusing it is more trustworthy than pulling a fresh ZIP from EDB's CDN
# and hoping it behaves identically. If no local install is found, it falls back to
# downloading the official EDB "binaries" ZIP (server only, no installer/pgAdmin/StackBuilder).
#
# VC++ redistributable: downloaded from Microsoft's own permanent aka.ms redirect, which
# always resolves to the latest supported build -- there is no versioned URL Microsoft
# publishes for this, so the SHA256 below was captured from an actual download rather than
# pinned to a specific release, and is re-verified on every run of this script.
#
#   powershell -ExecutionPolicy Bypass -File .\fetch-dependencies.ps1
# ---------------------------------------------------------------------------------------

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$ThirdParty = Join-Path $Root "third-party"
New-Item -ItemType Directory -Force -Path $ThirdParty | Out-Null

# --- PostgreSQL server binaries ---------------------------------------------------------
$pgDest = Join-Path $ThirdParty "pgsql"
$localPg = "C:\Program Files\PostgreSQL\17"

if (Test-Path (Join-Path $pgDest "bin\postgres.exe")) {
    Write-Host "[1/2] PostgreSQL binaries already staged at $pgDest -- skipping." -ForegroundColor Yellow
} elseif (Test-Path (Join-Path $localPg "bin\postgres.exe")) {
    Write-Host "[1/2] Copying PostgreSQL 17 from the local install ($localPg)..."
    New-Item -ItemType Directory -Force -Path $pgDest | Out-Null
    foreach ($sub in @("bin", "lib", "share")) {
        Copy-Item -Path (Join-Path $localPg $sub) -Destination (Join-Path $pgDest $sub) -Recurse -Force
    }
    # pgAdmin (a GUI tool, not needed to run the server) and its wxWidgets DLLs are the
    # bulk of "bin" that this app has no use for -- excluding them roughly halves the
    # payload with zero effect on pg_ctl/initdb/postgres/pg_dump/psql all still working.
    Get-ChildItem (Join-Path $pgDest "bin") -Filter "wx*.dll" -ErrorAction SilentlyContinue | Remove-Item -Force
    $pgAdminExe = Join-Path $pgDest "bin\pgAdmin4.exe"
    if (Test-Path $pgAdminExe) { Remove-Item $pgAdminExe -Force }
    $size = (Get-ChildItem $pgDest -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
    Write-Host ("      Staged {0:N1} MB." -f $size) -ForegroundColor Green
} else {
    Write-Host "[1/2] No local PostgreSQL 17 install found -- downloading EDB binaries ZIP..."
    $pgZipUrl = "https://get.enterprisedb.com/postgresql/postgresql-17.6-1-windows-x64-binaries.zip"
    $pgZip = Join-Path $env:TEMP "postgresql-17-binaries.zip"
    Invoke-WebRequest -Uri $pgZipUrl -OutFile $pgZip -UseBasicParsing
    $extractDir = Join-Path $env:TEMP "pg17-extract"
    Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -Path $pgZip -DestinationPath $extractDir -Force
    $pgSrc = Join-Path $extractDir "pgsql"
    New-Item -ItemType Directory -Force -Path $pgDest | Out-Null
    foreach ($sub in @("bin", "lib", "share")) {
        Copy-Item -Path (Join-Path $pgSrc $sub) -Destination (Join-Path $pgDest $sub) -Recurse -Force
    }
    Get-ChildItem (Join-Path $pgDest "bin") -Filter "wx*.dll" -ErrorAction SilentlyContinue | Remove-Item -Force
    Remove-Item $pgZip, $extractDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "      Downloaded and staged." -ForegroundColor Green
}

# Sanity check: the handful of binaries setup-database.ps1 actually shells out to.
foreach ($exe in @("postgres.exe", "pg_ctl.exe", "initdb.exe", "psql.exe", "pg_dump.exe", "pg_isready.exe")) {
    if (-not (Test-Path (Join-Path $pgDest "bin\$exe"))) {
        throw "Staged PostgreSQL is missing $exe -- the copy/download did not produce a usable server."
    }
}

# --- Visual C++ redistributable ---------------------------------------------------------
$vcRedist = Join-Path $ThirdParty "vc_redist.x64.exe"
# Captured from an actual download on 2026-09-18 -- re-verified below every run, so a
# change on Microsoft's side (a newer redistributable) is caught rather than silently
# shipped unverified; update this value after deliberately re-downloading and reviewing.
$expectedSha256 = "A7F4AB1E956DEA63D583632406FA0E38CF7DA01A98472F06B5936CFF67CE580F"

if (Test-Path $vcRedist) {
    $actual = (Get-FileHash $vcRedist -Algorithm SHA256).Hash
    if ($actual -eq $expectedSha256) {
        Write-Host "[2/2] VC++ redistributable already staged and verified." -ForegroundColor Yellow
    } else {
        Write-Host "[2/2] Staged VC++ redistributable does not match the pinned hash -- re-downloading..." -ForegroundColor Yellow
        Remove-Item $vcRedist -Force
    }
}
if (-not (Test-Path $vcRedist)) {
    Write-Host "[2/2] Downloading the VC++ 2015-2022 x64 redistributable..."
    Invoke-WebRequest -Uri "https://aka.ms/vs/17/release/vc_redist.x64.exe" -OutFile $vcRedist -UseBasicParsing
    $actual = (Get-FileHash $vcRedist -Algorithm SHA256).Hash
    if ($actual -ne $expectedSha256) {
        Remove-Item $vcRedist -Force
        throw "Downloaded VC++ redistributable hash ($actual) does not match the pinned value" `
            + " ($expectedSha256). Microsoft has published a newer build; verify it deliberately" `
            + " and update `$expectedSha256 in this script before proceeding."
    }
    Write-Host ("      Downloaded and verified ({0:N1} MB)." -f ((Get-Item $vcRedist).Length / 1MB)) -ForegroundColor Green
}

Write-Host "`nAll dependencies staged in $ThirdParty" -ForegroundColor Cyan
