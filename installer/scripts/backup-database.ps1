# ---------------------------------------------------------------------------------------
# Client-facing manual backup. Launched from the "Backup Database" Start Menu shortcut,
# double-clickable by a non-technical operator -- no arguments, no terminal knowledge
# required. Writes a timestamped pg_dump custom-format archive and prints where it went.
#
# This is deliberately separate from the app's own Backblaze offsite backup path
# (OffsiteBackupService, unaffected by this script) -- it's a simple local safety net an
# operator can run before anything risky (an upgrade, a big menu change) and copy off the
# machine by hand (USB stick, email, etc).
# ---------------------------------------------------------------------------------------

param(
    [string]$InstallDir = "C:\Program Files\Royal Pizza Sahowala",
    [string]$DataRoot   = "$env:ProgramData\Royal Pizza Sahowala",
    [int]$Port          = 5432
)

$ErrorActionPreference = "Stop"
$PgBin      = Join-Path $InstallDir "pgsql\bin"
$DbProps    = Join-Path $DataRoot "db.properties"
$BackupDir  = Join-Path $DataRoot "backups\manual"
$DbName     = "royal_pizza"
$AppRole    = "rps_app"

function Fail($msg) {
    Write-Host "`nBACKUP FAILED: $msg" -ForegroundColor Red
    Write-Host "Press Enter to close this window..."
    [void][System.Console]::ReadLine()
    exit 1
}

if (-not (Test-Path $DbProps)) {
    Fail "$DbProps not found -- is Royal Pizza Sahowala installed on this machine?"
}

# db.properties holds db.password=... ; pull it out rather than re-deriving the format,
# so this script keeps working even if setup-database.ps1's exact property list changes.
$passwordLine = Get-Content $DbProps | Where-Object { $_ -match '^\s*db\.password\s*=' } | Select-Object -First 1
if (-not $passwordLine) { Fail "db.properties does not contain a db.password entry." }
$appPassword = ($passwordLine -split '=', 2)[1].Trim()

New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$stamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$outFile = Join-Path $BackupDir "royal_pizza_$stamp.dump"

Write-Host "Backing up the Royal Pizza Sahowala database..."
Write-Host "Destination: $outFile`n"

$env:PGPASSWORD = $appPassword
try {
    & "$PgBin\pg_dump.exe" -h 127.0.0.1 -p $Port -U $AppRole -d $DbName --format=custom --file=$outFile
    $rc = $LASTEXITCODE
} finally {
    $env:PGPASSWORD = $null
}

if ($rc -ne 0 -or -not (Test-Path $outFile)) {
    Fail "pg_dump exited with code $rc. The database may be stopped -- check that Royal Pizza Sahowala is running."
}

$sizeMb = (Get-Item $outFile).Length / 1MB
Write-Host ("Backup complete: {0:N1} MB" -f $sizeMb) -ForegroundColor Green
Write-Host "Saved to: $outFile"
Write-Host "`nCopy this file somewhere off this PC (a USB drive, email, cloud storage) for real disaster protection."
Write-Host "`nPress Enter to close this window..."
[void][System.Console]::ReadLine()
