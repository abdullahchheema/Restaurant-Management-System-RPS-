# ---------------------------------------------------------------------------------------
# Uninstall-time database teardown. Run by royalpizza.iss's [UninstallRun] section.
#
# Data-safe by default: stops and unregisters the Windows service and leaves everything
# under ProgramData (pgdata, db.properties, logs, backups) completely untouched. Only
# with -DeleteData does it also remove the data directory -- the .iss script only passes
# that switch when the operator has explicitly ticked the "also delete the database and
# all business data" uninstall checkbox and confirmed a second time.
# ---------------------------------------------------------------------------------------

param(
    [string]$InstallDir  = "C:\Program Files\Royal Pizza Sahowala",
    [string]$DataRoot    = "$env:ProgramData\Royal Pizza Sahowala",
    [string]$ServiceName = "RoyalPizzaSahowalaDB",
    [switch]$DeleteData
)

$ErrorActionPreference = "Stop"
$PgBin  = Join-Path $InstallDir "pgsql\bin"
$PgData = Join-Path $DataRoot "pgdata"

function Log($msg) { Write-Host "[remove-database] $msg" }

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($svc) {
    if ($svc.Status -ne 'Stopped') {
        Log "Stopping service $ServiceName..."
        Stop-Service -Name $ServiceName -ErrorAction SilentlyContinue
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Service -Name $ServiceName).Status -ne 'Stopped' -and (Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
        }
    }
    Log "Unregistering service $ServiceName..."
    if (Test-Path "$PgBin\pg_ctl.exe") {
        & "$PgBin\pg_ctl.exe" unregister -N $ServiceName
    } else {
        # pgsql\bin is already gone (Program Files was removed before this ran) -- fall
        # back to sc.exe, which only needs the service name, not the original binary.
        sc.exe delete $ServiceName | Out-Null
    }
} else {
    Log "Service $ServiceName is not registered -- nothing to stop."
}

if ($DeleteData) {
    Log "Deleting data directory $PgData (operator confirmed data removal)..."
    Remove-Item -Path $PgData -Recurse -Force -ErrorAction SilentlyContinue
    Log "Data directory removed. Logs and backups under $DataRoot are left in place."
} else {
    Log "Leaving $DataRoot untouched -- pgdata, db.properties, logs and backups all survive."
}

Log "Database teardown complete."
exit 0
