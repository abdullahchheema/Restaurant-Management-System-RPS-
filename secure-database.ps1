# ---------------------------------------------------------------------------------------
# One-time database hardening for Royal Pizza Sahowala.
#
# Run this ONCE, on the machine hosting PostgreSQL, before handing the system to the client.
# It does three things that could not be automated safely from a code assistant, because
# each one changes live database state:
#
#   1. Renames the database from "pgAdmin" (a tool's name, not a database's) to royal_pizza.
#   2. Creates a dedicated, non-superuser role for the app and hands it ownership, so the
#      credential sitting in db.properties on the shop's till is no longer a superuser
#      login for the whole PostgreSQL server.
#   3. Rotates the postgres superuser password, which is required because the old one was
#      committed to a public GitHub repository and must be treated as compromised.
#
# It takes a full backup first and refuses to continue if anything is still connected.
# Nothing is dropped: the old role and the backup both remain, so this is reversible.
#
#   powershell -ExecutionPolicy Bypass -File .\secure-database.ps1
# ---------------------------------------------------------------------------------------

$ErrorActionPreference = "Stop"

$PgBin       = "C:\Program Files\PostgreSQL\17\bin"
$OldDbName   = "pgAdmin"
$NewDbName   = "royal_pizza"
$AppRole     = "rps_app"
$ProjectRoot = $PSScriptRoot

$psql    = Join-Path $PgBin "psql.exe"
$pgDump  = Join-Path $PgBin "pg_dump.exe"
foreach ($exe in @($psql, $pgDump)) {
    if (-not (Test-Path $exe)) { throw "Not found: $exe -- adjust `$PgBin at the top of this script." }
}

function New-StrongPassword {
    # Letters and digits only: this value travels through JDBC URLs, PGPASSWORD and a
    # .properties file, and punctuation has a different escaping rule in each of them.
    # [char] casts are required: Windows PowerShell 5.1's range operator only accepts
    # integers, so a bare 'a'..'z' throws rather than producing letters.
    $chars = [char[]]([char]'a'..[char]'z') +
             [char[]]([char]'A'..[char]'Z') +
             [char[]]([char]'0'..[char]'9')
    -join (1..28 | ForEach-Object { $chars | Get-Random })
}

Write-Host "`nRoyal Pizza Sahowala - database hardening`n" -ForegroundColor Cyan

$currentPassword = Read-Host "Current postgres password" -AsSecureString
$currentPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($currentPassword))
$env:PGPASSWORD = $currentPlain

& $psql -U postgres -h localhost -d postgres -tAc "SELECT 1" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not connect with that password. Nothing has been changed." }
Write-Host "  Connected." -ForegroundColor Green

# --- which database are we working on? ------------------------------------------------
$dbToUse = $OldDbName
$exists = & $psql -U postgres -h localhost -d postgres -tAc `
    "SELECT 1 FROM pg_database WHERE datname = '$OldDbName'"
if ($exists -ne "1") {
    $renamed = & $psql -U postgres -h localhost -d postgres -tAc `
        "SELECT 1 FROM pg_database WHERE datname = '$NewDbName'"
    if ($renamed -eq "1") {
        Write-Host "  '$OldDbName' not found but '$NewDbName' exists -- rename already done." -ForegroundColor Yellow
        $dbToUse = $NewDbName
    } else {
        throw "Neither '$OldDbName' nor '$NewDbName' exists on this server."
    }
}

# --- 0. backup ------------------------------------------------------------------------
$backupDir = Join-Path $ProjectRoot "backups"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$backupFile = Join-Path $backupDir ("pre-hardening-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".dump")
Write-Host "`n[0/3] Backing up '$dbToUse'..."
& $pgDump -U postgres -h localhost -d $dbToUse --format=custom --compress=6 --file=$backupFile
if ($LASTEXITCODE -ne 0) { throw "Backup failed -- stopping before any change is made." }
Write-Host ("  Saved {0} ({1:N2} MB)" -f $backupFile, ((Get-Item $backupFile).Length / 1MB)) -ForegroundColor Green

# --- 1. rename ------------------------------------------------------------------------
if ($dbToUse -eq $OldDbName) {
    Write-Host "`n[1/3] Renaming '$OldDbName' -> '$NewDbName'..."
    $busy = & $psql -U postgres -h localhost -d postgres -tAc `
        "SELECT count(*) FROM pg_stat_activity WHERE datname = '$OldDbName'"
    if ([int]$busy -gt 0) {
        throw "$busy session(s) still connected to '$OldDbName'. Close the app and pgAdmin, then re-run."
    }
    # Built via format('%I', ...) instead of a literal double-quoted identifier on the
    # command line: PowerShell 5.1 mangles a native-exe argument that itself contains
    # embedded double quotes, which silently dropped the quoting and made Postgres fold
    # "pgAdmin" to lowercase "pgadmin" (reproduced live -- that's the error above).
    $renameSql = "DO " + '$$' + " BEGIN EXECUTE format('ALTER DATABASE %I RENAME TO %I', '$OldDbName', '$NewDbName'); END " + '$$' + ";"
    & $psql -U postgres -h localhost -d postgres -v ON_ERROR_STOP=1 -c $renameSql
    if ($LASTEXITCODE -ne 0) { throw "Rename failed. The database is untouched; backup is at $backupFile" }
    Write-Host "  Renamed." -ForegroundColor Green
} else {
    Write-Host "`n[1/3] Rename already done -- skipping." -ForegroundColor Yellow
}

# --- 2. dedicated application role ----------------------------------------------------
Write-Host "`n[2/3] Creating the '$AppRole' application role..."
$appPassword = New-StrongPassword
$roleExists = & $psql -U postgres -h localhost -d postgres -tAc `
    "SELECT 1 FROM pg_roles WHERE rolname = '$AppRole'"

if ($roleExists -eq "1") {
    Write-Host "  Role already exists -- resetting its password." -ForegroundColor Yellow
    & $psql -U postgres -h localhost -d postgres -v ON_ERROR_STOP=1 `
        -c "ALTER ROLE $AppRole WITH LOGIN PASSWORD '$appPassword';"
} else {
    & $psql -U postgres -h localhost -d postgres -v ON_ERROR_STOP=1 `
        -c "CREATE ROLE $AppRole WITH LOGIN PASSWORD '$appPassword';"
}
if ($LASTEXITCODE -ne 0) { throw "Could not create or update the $AppRole role." }

# Owning the database (rather than being a superuser) is what lets the app still run its
# own schema migrations while having no rights at all over the rest of the server.
& $psql -U postgres -h localhost -d postgres -v ON_ERROR_STOP=1 `
    -c "ALTER DATABASE $NewDbName OWNER TO $AppRole;"
if ($LASTEXITCODE -ne 0) { throw "Could not hand $NewDbName to $AppRole." }

# Each application object is reassigned individually rather than with REASSIGN OWNED BY
# postgres: postgres is a superuser and also owns catalog objects that are "required by
# the database system" and can never be reassigned, so the bulk form always fails.
# Restricting the loops to schema 'public' touches only this app's own tables.
# Run from a file, not -c: psql gets the SQL verbatim, with no PowerShell re-quoting.
$ownershipSql = @'
DO $do$
DECLARE r record;
BEGIN
    FOR r IN SELECT tablename AS n FROM pg_tables WHERE schemaname = 'public' LOOP
        EXECUTE format('ALTER TABLE public.%I OWNER TO %I', r.n, '__ROLE__');
    END LOOP;
    FOR r IN SELECT sequencename AS n FROM pg_sequences WHERE schemaname = 'public' LOOP
        EXECUTE format('ALTER SEQUENCE public.%I OWNER TO %I', r.n, '__ROLE__');
    END LOOP;
    FOR r IN SELECT viewname AS n FROM pg_views WHERE schemaname = 'public' LOOP
        EXECUTE format('ALTER VIEW public.%I OWNER TO %I', r.n, '__ROLE__');
    END LOOP;
    FOR r IN SELECT p.oid::regprocedure AS n FROM pg_proc p
             JOIN pg_namespace ns ON ns.oid = p.pronamespace
             WHERE ns.nspname = 'public' LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO %I', r.n, '__ROLE__');
    END LOOP;
END
$do$;
ALTER SCHEMA public OWNER TO __ROLE__;
GRANT ALL ON SCHEMA public TO __ROLE__;
'@.Replace('__ROLE__', $AppRole)

$ownershipFile = Join-Path $env:TEMP "rps-ownership.sql"
[System.IO.File]::WriteAllText($ownershipFile, $ownershipSql, (New-Object System.Text.UTF8Encoding($false)))
try {
    & $psql -U postgres -h localhost -d $NewDbName -v ON_ERROR_STOP=1 -f $ownershipFile
    if ($LASTEXITCODE -ne 0) { throw "Could not transfer ownership to $AppRole." }
} finally {
    Remove-Item $ownershipFile -Force -ErrorAction SilentlyContinue
}
Write-Host "  Role created and granted ownership of $NewDbName." -ForegroundColor Green

# Prove the app can actually connect and read before we rewrite db.properties.
$env:PGPASSWORD = $appPassword
$check = & $psql -U $AppRole -h localhost -d $NewDbName -tAc "SELECT count(*) FROM customer_order"
if ($LASTEXITCODE -ne 0) { throw "The $AppRole role cannot query the database -- db.properties left unchanged." }
Write-Host "  Verified: $AppRole reads $check order(s)." -ForegroundColor Green
$env:PGPASSWORD = $currentPlain

# --- 3. rotate the superuser password -------------------------------------------------
Write-Host "`n[3/3] Rotating the postgres superuser password..."
$newSuperPassword = New-StrongPassword
& $psql -U postgres -h localhost -d postgres -v ON_ERROR_STOP=1 `
    -c "ALTER ROLE postgres WITH PASSWORD '$newSuperPassword';"
if ($LASTEXITCODE -ne 0) { throw "Could not rotate the postgres password." }
Write-Host "  Rotated." -ForegroundColor Green

# --- rewrite db.properties ------------------------------------------------------------
$dbProps = Join-Path $ProjectRoot "db.properties"
$dbPropsLines = @(
    "# Written by secure-database.ps1 -- this file is gitignored and must stay that way.",
    # One interpolated string, never "..." + "...": inside an array literal PowerShell
    # binds the comma tighter than +, so the concatenation swallowed the following
    # elements and split the URL across two lines, dropping the query parameter.
    "db.url=jdbc:postgresql://localhost:5432/${NewDbName}?reWriteBatchedInserts=true",
    "db.user=$AppRole",
    "db.password=$appPassword"
)
# Written without a BOM on purpose: Set-Content -Encoding utf8 emits one on Windows
# PowerShell 5.1, and Java's Properties.load() reads ISO-8859-1, so those three bytes
# would turn the first line into a junk key instead of a comment.
[System.IO.File]::WriteAllLines($dbProps, $dbPropsLines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "`n  Updated $dbProps" -ForegroundColor Green

$env:PGPASSWORD = $null

Write-Host "`n=======================================================================" -ForegroundColor Cyan
Write-Host " Done. Write these down and store them somewhere safe NOW --"
Write-Host " they are not recoverable from this script."
Write-Host "=======================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  postgres (superuser) password  : $newSuperPassword"
Write-Host "  $AppRole (application) password : $appPassword"
Write-Host ""
Write-Host "  Database renamed to            : $NewDbName"
Write-Host "  Backup taken before changes    : $backupFile"
Write-Host ""
Write-Host " Next: update qa\run\db.properties by hand if you still run the QA suites,"
Write-Host " and re-point any saved pgAdmin connection at the new name and password."
Write-Host ""
