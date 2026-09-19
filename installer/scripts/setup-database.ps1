# ---------------------------------------------------------------------------------------
# Install-time database bootstrap. Run by royalpizza.iss's [Run] section after files are
# copied, and safe to re-run (upgrade, or a retried/interrupted install): every step
# checks whether it already happened before doing anything, so nothing here can corrupt
# an existing install by running twice.
#
# On exit:
#   0  = database is installed, running, and the app's schema/menu/manager verified ready
#   1+ = something failed; the installer must show its own failure page, not declare
#        success
#
# Bootstrapping order matters: the server starts with a permissive pg_hba.conf (no
# passwords needed yet, since none exist), passwords are set once the server is
# reachable, and ONLY THEN is pg_hba.conf tightened to require them -- there is no
# interactive prompt anywhere in this sequence, which a silent/unattended install cannot
# provide one for.
# ---------------------------------------------------------------------------------------

param(
    [string]$InstallDir  = "C:\Program Files\Royal Pizza Sahowala",
    [string]$DataRoot    = "$env:ProgramData\Royal Pizza Sahowala",
    # Overridable purely so this script can be exercised end-to-end against a disposable
    # instance without touching the real service/port -- the installer itself never
    # passes these, so a real install always gets the real values below.
    [string]$ServiceName = "RoyalPizzaSahowalaDB",
    [int]$Port           = 5432
)

$ErrorActionPreference = "Stop"
$PgBin       = Join-Path $InstallDir "pgsql\bin"
$PgData      = Join-Path $DataRoot "pgdata"
$LogsRoot    = Join-Path $DataRoot "logs"
$PgLogDir    = Join-Path $LogsRoot "postgresql"
$BackupsDir  = Join-Path $DataRoot "backups"
$DbProps     = Join-Path $DataRoot "db.properties"
$CredsFile   = Join-Path $LogsRoot "install-credentials.txt"
$DbName      = "royal_pizza"
$AppRole     = "rps_app"

function Log($msg) { Write-Host "[setup-database] $msg" }

function New-StrongPassword {
    # Letters and digits only -- this value passes through a JDBC URL, PGPASSWORD, and a
    # .properties file, each with a different escaping rule; alphanumeric sidesteps all
    # of them at once (same reasoning as secure-database.ps1's password generator).
    $chars = [char[]]([char]'a'..[char]'z') + [char[]]([char]'A'..[char]'Z') + [char[]]([char]'0'..[char]'9')
    -join (1..28 | ForEach-Object { $chars | Get-Random })
}

function Invoke-Psql([string]$Database, [string]$Sql, [string]$AsUser = "postgres") {
    $tmp = [System.IO.Path]::GetTempFileName() + ".sql"
    [System.IO.File]::WriteAllText($tmp, $Sql, (New-Object System.Text.UTF8Encoding($false)))
    try {
        # psql's own stdout must not reach this function's output stream -- anything that
        # does gets bundled into the caller's `$rc = Invoke-Psql ...` alongside the
        # `return $LASTEXITCODE` below, turning $rc into an array and making a later
        # `$rc -ne 0` match on the stray text instead of the actual exit code.
        & "$PgBin\psql.exe" -U $AsUser -h 127.0.0.1 -p $Port -d $Database -v ON_ERROR_STOP=1 -f $tmp | Out-Null
        return $LASTEXITCODE
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Write-PgHba([string]$Method) {
    @"
# Written by setup-database.ps1 -- do not edit by hand, it is regenerated on every install/upgrade.
# Local-only: no entry here can ever match a connection arriving from another machine.
host    all             all             127.0.0.1/32            $Method
host    all             all             ::1/128                 $Method
"@ | Set-Content -Path (Join-Path $PgData "pg_hba.conf") -Encoding ASCII
}

New-Item -ItemType Directory -Force -Path $DataRoot, $LogsRoot, $PgLogDir, $BackupsDir | Out-Null

# %ProgramData% is Administrators/SYSTEM-writable by default but not necessarily
# Users-writable everywhere -- the app runs as whichever account is logged in at the
# till, and needs to write app.log and stage backups here.
try {
    icacls $DataRoot /grant "*S-1-5-32-545:(OI)(CI)M" /T | Out-Null   # BUILTIN\Users, by SID (locale-independent)
} catch { Log "Could not adjust ProgramData ACL (non-fatal): $_" }

$freshInit = -not (Test-Path (Join-Path $PgData "PG_VERSION"))

if ($freshInit) {
    Log "No existing data directory -- initializing a new database."
    New-Item -ItemType Directory -Force -Path $PgData | Out-Null

    & "$PgBin\initdb.exe" -D $PgData -U postgres -E UTF8 --locale=C --no-locale
    if ($LASTEXITCODE -ne 0) { throw "initdb failed (exit $LASTEXITCODE)." }

    # Small-footprint settings for an i3 2nd-gen / low-RAM till, not server defaults --
    # this app opens exactly two connections total (Db.java: one read, one write), so
    # max_connections=20 has enormous headroom, not a real limit.
    @"
listen_addresses = 'localhost'
port = $Port
max_connections = 20
shared_buffers = 128MB
work_mem = 4MB
maintenance_work_mem = 32MB
effective_cache_size = 512MB
logging_collector = on
log_directory = '$($PgLogDir -replace '\\','/')'
log_filename = 'postgresql-%Y-%m-%d.log'
log_rotation_age = 1d
log_rotation_size = 10MB
"@ | Add-Content -Path (Join-Path $PgData "postgresql.conf") -Encoding ASCII

    # Trust for now -- no password exists yet to require. Tightened to scram-sha-256
    # below, once the server is up and passwords have actually been set.
    Write-PgHba "trust"

    # NetworkService needs full control of its own data directory; PostgreSQL refuses to
    # run at all under an Administrator account on Windows, which is exactly why a
    # low-privilege built-in service account is used instead of creating a dedicated
    # Windows user (and having to store ITS password somewhere).
    icacls $PgData /grant "NT AUTHORITY\NetworkService:(OI)(CI)F" /T | Out-Null
    icacls $PgLogDir /grant "NT AUTHORITY\NetworkService:(OI)(CI)F" /T | Out-Null

    & "$PgBin\pg_ctl.exe" register -N $ServiceName -D $PgData -U "NT AUTHORITY\NetworkService" -S auto
    if ($LASTEXITCODE -ne 0) { throw "Registering the $ServiceName service failed (exit $LASTEXITCODE)." }
} else {
    Log "Existing data directory found at $PgData -- treating this as an upgrade, not a fresh install."
    if (-not (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue)) {
        Log "Service $ServiceName is not registered (unexpected on an upgrade) -- registering it now."
        & "$PgBin\pg_ctl.exe" register -N $ServiceName -D $PgData -U "NT AUTHORITY\NetworkService" -S auto
    }
}

Log "Starting service $ServiceName..."
Start-Service -Name $ServiceName -ErrorAction Stop

Log "Waiting for PostgreSQL to accept connections..."
$deadline = (Get-Date).AddSeconds(60)
$ready = $false
while ((Get-Date) -lt $deadline) {
    & "$PgBin\pg_isready.exe" -h 127.0.0.1 -p $Port 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw "PostgreSQL did not become ready within 60 seconds." }
Log "PostgreSQL is ready."

# --- credentials and roles --------------------------------------------------------------
if ($freshInit) {
    Log "Setting passwords and creating the application role..."
    $superPassword = New-StrongPassword
    $appPassword   = New-StrongPassword

    $env:PGPASSWORD = ""   # trust auth: no password needed yet
    $rc = Invoke-Psql -Database "postgres" -Sql "ALTER ROLE postgres WITH PASSWORD '$superPassword';"
    if ($rc -ne 0) { throw "Could not set the postgres superuser password." }

    $env:PGPASSWORD = $superPassword
    $rc = Invoke-Psql -Database "postgres" -Sql @"
CREATE ROLE $AppRole WITH LOGIN PASSWORD '$appPassword';
CREATE DATABASE $DbName OWNER $AppRole;
"@
    if ($rc -ne 0) { throw "Could not create the $AppRole role or the $DbName database." }

    # Now that both passwords exist, close the door: nothing after this point can connect
    # without one, including on this same machine.
    Write-PgHba "scram-sha-256"
    & "$PgBin\pg_ctl.exe" reload -D $PgData

    [System.IO.File]::WriteAllLines($CredsFile, @(
        "Royal Pizza Sahowala -- generated at install time. Store this somewhere safe,",
        "then delete this file; it is not recoverable if lost.",
        "",
        "postgres (superuser) password : $superPassword",
        "$AppRole (application) password : $appPassword"
    ), (New-Object System.Text.UTF8Encoding($false)))
    icacls $CredsFile /inheritance:r /grant:r "BUILTIN\Administrators:F" "NT AUTHORITY\SYSTEM:F" | Out-Null

    [System.IO.File]::WriteAllLines($DbProps, @(
        "# Written by setup-database.ps1 at install time. Do not edit by hand.",
        # ${DbName} (braced) is load-bearing: PowerShell's simple "$Name" variable-name
        # token includes "?", so "$DbName?reWriteBatchedInserts" parses as one (nonexistent)
        # variable reference and silently drops everything but the trailing "=true" --
        # confirmed empirically after the unbraced version produced a truncated URL.
        "db.url=jdbc:postgresql://localhost:$Port/${DbName}?reWriteBatchedInserts=true",
        "db.user=$AppRole",
        "db.password=$appPassword"
    ), (New-Object System.Text.UTF8Encoding($false)))
    # Readable by any logged-in user (the app runs as whoever is signed in at the till),
    # writable only by an administrator -- a cashier can never edit their own DB
    # credential, deliberately or by an accidental keystroke in a text editor.
    icacls $DbProps /inheritance:r /grant:r "BUILTIN\Administrators:F" "NT AUTHORITY\SYSTEM:F" "*S-1-5-32-545:RX" | Out-Null

    $env:PGPASSWORD = $null
} else {
    Log "Existing role/database/credentials left untouched (this is an upgrade)."
    if (-not (Test-Path $DbProps)) {
        throw "pgdata exists but db.properties is missing -- refusing to guess at credentials. " `
            + "This install is in an inconsistent state and needs manual repair."
    }
}

# --- schema, seed, verification ----------------------------------------------------------
Log "Running schema migrations and verifying the database..."
$runtimeJava = Join-Path $InstallDir "runtime\bin\java.exe"
$appJar      = Join-Path $InstallDir "app\RoyalPizzaSahowala.jar"
$libDir      = Join-Path $InstallDir "app\lib\*"

& $runtimeJava "-Drps.config=$DbProps" -cp "$appJar;$libDir" rps.app.Bootstrap
if ($LASTEXITCODE -ne 0) { throw "Bootstrap (schema/seed/verify) failed (exit $LASTEXITCODE)." }

Log "Database setup complete."
exit 0
