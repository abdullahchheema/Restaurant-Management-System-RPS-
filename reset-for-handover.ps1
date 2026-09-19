# ---------------------------------------------------------------------------------------
# Clears the development/testing data so the client's till starts from zero.
#
# Run this ONCE, immediately before handing the system over.
#
# CLEARED (all trading history):
#     customer_order, order_line, order_status_history, daily_counter
#
# KEPT (the shop's configuration - the client needs a working till, not an empty one):
#     category, menu_item, menu_item_variant, option_group, option_value, schema_version
#
#   The menu is deliberately kept. Migration 9 seeded it and is already recorded as
#   applied, so wiping those tables would NOT re-seed them - the client would open the
#   till to an empty menu with no way back short of re-entering 87 items by hand.
#
# STAFF: the four development accounts are replaced by a single clean manager
#   (id 1 / admin123). The app forces that password to be changed at first sign-in.
#
# A full backup is taken first, and the whole reset runs in ONE transaction - any failure
# rolls back and leaves the database exactly as it was.
#
#   powershell -ExecutionPolicy Bypass -File .\reset-for-handover.ps1
# ---------------------------------------------------------------------------------------

$ErrorActionPreference = "Stop"

$PgBin       = "C:\Program Files\PostgreSQL\17\bin"
$ProjectRoot = $PSScriptRoot

$psql   = Join-Path $PgBin "psql.exe"
$pgDump = Join-Path $PgBin "pg_dump.exe"
foreach ($exe in @($psql, $pgDump)) {
    if (-not (Test-Path $exe)) { throw "Not found: $exe -- adjust `$PgBin at the top of this script." }
}

# Connection details come from db.properties so this can never be pointed somewhere else
# by accident, and so no credential has to be typed or stored in this file.
$propsPath = Join-Path $ProjectRoot "db.properties"
if (-not (Test-Path $propsPath)) { throw "db.properties not found next to this script." }
$props = @{}
Get-Content $propsPath | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    $props[$k.Trim()] = $v.Trim()
}
if ($props['db.url'] -notmatch '/([^/?]+)(\?|$)') { throw "Could not read the database name from db.url." }
$dbName = $Matches[1]
$dbUser = $props['db.user']
$env:PGPASSWORD = $props['db.password']

Write-Host "`nRoyal Pizza Sahowala - reset for client handover`n" -ForegroundColor Cyan
Write-Host "  Database : $dbName"
Write-Host "  As user  : $dbUser`n"

& $psql -U $dbUser -h localhost -d $dbName -tAc "SELECT 1" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not connect. Nothing has been changed." }

Write-Host "Current contents:" -ForegroundColor Cyan
& $psql -U $dbUser -h localhost -d $dbName -c @"
SELECT 'customer_order' AS table, count(*) FROM customer_order
UNION ALL SELECT 'order_line', count(*) FROM order_line
UNION ALL SELECT 'order_status_history', count(*) FROM order_status_history
UNION ALL SELECT 'daily_counter', count(*) FROM daily_counter
UNION ALL SELECT 'staff', count(*) FROM staff
UNION ALL SELECT 'menu_item  (KEPT)', count(*) FROM menu_item
UNION ALL SELECT 'category   (KEPT)', count(*) FROM category
ORDER BY 1;
"@

Write-Host "This permanently deletes all order history." -ForegroundColor Yellow
Write-Host "The menu, categories and prices are kept.`n" -ForegroundColor Yellow
$answer = Read-Host "Type CLEAR to proceed (anything else cancels)"
if ($answer -cne "CLEAR") {
    Write-Host "`nCancelled. Nothing was changed.`n" -ForegroundColor Green
    exit 0
}

# --- backup ---------------------------------------------------------------------------
$backupDir = Join-Path $ProjectRoot "backups"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$backupFile = Join-Path $backupDir ("pre-handover-reset-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".dump")
Write-Host "`n[1/2] Backing up to $backupFile ..."
& $pgDump -U $dbUser -h localhost -d $dbName --format=custom --compress=6 --file=$backupFile
if ($LASTEXITCODE -ne 0) { throw "Backup failed -- stopping before anything is deleted." }
Write-Host ("  Saved ({0:N2} MB)" -f ((Get-Item $backupFile).Length / 1MB)) -ForegroundColor Green

# --- reset ----------------------------------------------------------------------------
# From a file rather than -c: psql receives the SQL verbatim, with no PowerShell
# re-quoting of the embedded dollar-quoted block or the single-quoted literals.
Write-Host "`n[2/2] Clearing trading data ..."

$resetSql = @'
BEGIN;

-- TRUNCATE rather than DELETE: it also resets the identity sequences, so the client's
-- first order is id 1 and order number ...-001 rather than continuing from 2618.
-- CASCADE follows the foreign keys from customer_order into its children.
TRUNCATE TABLE
    order_status_history,
    order_line,
    customer_order,
    daily_counter
RESTART IDENTITY CASCADE;

-- The development logins go. staff is not truncated with the tables above because
-- customer_order.staff_id references it; with those rows already gone this is safe.
DELETE FROM staff;

-- One clean manager for the client. The password is the documented default, which
-- LoginWindow refuses to let anyone actually work under -- it forces a change at the
-- first sign-in, so this value never survives the client's first day.
INSERT INTO staff (id, password, first_name, last_name, role, active)
VALUES (1, 'admin123', 'Manager', 'Account', 'MANAGER', TRUE);

SELECT setval(pg_get_serial_sequence('staff', 'id'), 1, true);

COMMIT;
'@

$sqlFile = Join-Path $env:TEMP "rps-handover-reset.sql"
[System.IO.File]::WriteAllText($sqlFile, $resetSql, (New-Object System.Text.UTF8Encoding($false)))
try {
    & $psql -U $dbUser -h localhost -d $dbName -v ON_ERROR_STOP=1 -f $sqlFile
    if ($LASTEXITCODE -ne 0) {
        throw "Reset failed and was rolled back. The database is unchanged; backup is at $backupFile"
    }
} finally {
    Remove-Item $sqlFile -Force -ErrorAction SilentlyContinue
}

# The seeded password is stored in plaintext above; migration 12 hashes any such row on
# the next startup, exactly as it does for a fresh install.
Write-Host "  Cleared." -ForegroundColor Green

Write-Host "`nAfter reset:" -ForegroundColor Cyan
& $psql -U $dbUser -h localhost -d $dbName -c @"
SELECT 'customer_order' AS table, count(*) FROM customer_order
UNION ALL SELECT 'order_line', count(*) FROM order_line
UNION ALL SELECT 'order_status_history', count(*) FROM order_status_history
UNION ALL SELECT 'daily_counter', count(*) FROM daily_counter
UNION ALL SELECT 'staff', count(*) FROM staff
UNION ALL SELECT 'menu_item  (KEPT)', count(*) FROM menu_item
UNION ALL SELECT 'category   (KEPT)', count(*) FROM category
ORDER BY 1;
"@

$env:PGPASSWORD = $null

Write-Host "=======================================================================" -ForegroundColor Cyan
Write-Host " Ready for handover."
Write-Host "=======================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  First sign-in : staff ID 1, password admin123"
Write-Host "                  (the app forces a new password before the till opens)"
Write-Host ""
Write-Host "  Menu kept     : the shop's full catalogue is intact"
Write-Host "  Backup        : $backupFile"
Write-Host ""
Write-Host " Start the app and place one test order to confirm it numbers from -001,"
Write-Host " then delete that order's receipt files from Documents\RoyalPizzaSahowala."
Write-Host ""
