# Deployment guide — Royal Pizza Sahowala

How to build the Windows installer, what it does on the client's machine, and how to
back up, upgrade, and troubleshoot an installed system.

## Architecture

A single Java 25 Swing process talks directly to a local PostgreSQL 17 server over
JDBC — there is no web server, no REST API, no separate frontend. Only PostgreSQL runs
continuously as a Windows service; the app itself only runs while a cashier is using it.

```
C:\Program Files\Royal Pizza Sahowala\      <- replaced on every upgrade
  RoyalPizzaSahowala.exe                      jpackage launcher (bundled JRE inside)
  app\    RoyalPizzaSahowala.jar + flatlaf + postgresql-42.7.3 jars
  runtime\                                    jlink'd JRE (~50 MB)
  pgsql\bin|lib|share\                        PostgreSQL 17 server binaries
  scripts\                                    setup/remove/backup .ps1

C:\ProgramData\Royal Pizza Sahowala\        <- survives every upgrade and uninstall
  db.properties                               generated credentials, ACL-restricted
  pgdata\                                      PostgreSQL data directory
  logs\app.log, logs\postgresql\, logs\install-credentials.txt
  backups\manual\                              output of the "Backup Database" shortcut

%USERPROFILE%\Documents\RoyalPizzaSahowala\ <- per Windows user account
  settings.properties, receipts\, exports\
```

The database service (`RoyalPizzaSahowalaDB`) runs as `NT AUTHORITY\NetworkService`,
listens on `127.0.0.1:5432` only, and is never reachable from another machine.

## Building the installer

Needs: this repo, JDK 25 at `C:\Program Files\Java\jdk-25.0.4.1`, Inno Setup 6 (installed
once on the dev machine — never shipped to a client).

```powershell
cd installer
.\fetch-dependencies.ps1   # once: stages PostgreSQL binaries + the VC++ redistributable
.\build-installer.ps1      # every release: compile -> jlink -> jpackage -> ISCC
```

Produces `installer\out\RoyalPizzaSahowala-Setup-<version>.exe`. `installer\build`,
`installer\third-party`, and `installer\out` are all gitignored and fully reproducible —
nothing in them needs to be committed.

Bumping the version: edit `#define AppVersion` in `installer\royalpizza.iss` and
`$AppVersion` in `installer\build-installer.ps1` together (kept in sync by hand, not by a
shared file, since Inno's preprocessor and PowerShell don't share config format).

## Installing on the client machine

Requirements: Windows 10/11 x64. No Java, no PostgreSQL, no prerequisites — the installer
is fully self-contained and works offline.

1. Run `RoyalPizzaSahowala-Setup-<version>.exe`, accept the UAC prompt (installation
   needs Administrator rights to register the database service).
2. The installer silently installs the VC++ redistributable (skipped if already present),
   then runs `setup-database.ps1`, which initializes PostgreSQL, generates two random
   passwords, creates the `royal_pizza` database and `rps_app` role, and runs all schema
   migrations — this can take up to a minute on first install.
3. On success, launch from the Start Menu or desktop shortcut.
4. **Immediately after install**, open
   `C:\ProgramData\Royal Pizza Sahowala\logs\install-credentials.txt`, copy the
   `postgres` superuser password somewhere safe (a password manager, not this PC), then
   delete the file. It is not recoverable if lost, and is not needed for normal
   operation — the app only ever uses the `rps_app` role.
5. Log in with staff ID `1` and the default password; the app forces an immediate
   password change.

### Upgrading

Just run a newer `Setup.exe` over the existing install. `pgdata`, `db.properties`, logs,
and backups are never touched by an upgrade — only `C:\Program Files\...` is replaced,
and schema migrations (already idempotent) bring the existing database up to date.
Verify the order count is unchanged after upgrading.

### Uninstalling

Use "Apps & Features" as usual. You'll be asked whether to also permanently delete the
database and all business data — the default is **no**, which leaves everything under
`ProgramData` intact so a reinstall picks up exactly where you left off. Only a second,
explicit confirmation deletes real data.

## Backups

Start Menu → "Backup Database" writes a timestamped `pg_dump` archive to
`C:\ProgramData\Royal Pizza Sahowala\backups\manual\`. Run it before anything risky (an
upgrade, a big menu change) and copy the resulting `.dump` file off the machine — a USB
drive, email, cloud storage — since a backup sitting on the same disk as the live
database protects against nothing except an operator mistake.

This is separate from — and unaffected by — the app's own optional Backblaze B2 offsite
backup path, which self-disables cleanly if left unconfigured.

### Restoring from a backup

```
"C:\Program Files\Royal Pizza Sahowala\pgsql\bin\pg_restore.exe" ^
  -h 127.0.0.1 -p 5432 -U rps_app -d royal_pizza --clean ^
  "C:\ProgramData\Royal Pizza Sahowala\backups\manual\royal_pizza_<timestamp>.dump"
```
Enter the `rps_app` password from `db.properties` when prompted (or set `PGPASSWORD`
first). Stop the app before restoring so nothing writes to the database mid-restore.

## Troubleshooting

- **App won't start / can't connect**: check
  `C:\ProgramData\Royal Pizza Sahowala\logs\app.log` first, then confirm the
  `RoyalPizzaSahowalaDB` service is `Running` (Services app, or
  `Get-Service RoyalPizzaSahowalaDB` in an elevated PowerShell).
- **Service won't start**: check `C:\ProgramData\Royal Pizza Sahowala\logs\postgresql\`
  for the PostgreSQL server's own log. A common cause on a locked-down machine is the
  `NetworkService` account lacking access to `C:\Program Files\Royal Pizza Sahowala\` —
  the default ACL there grants Authenticated Users read+execute, so this should not
  happen on a stock Windows install.
- **Reinstalling after an interrupted install**: just run `Setup.exe` again.
  `setup-database.ps1` checks what already happened at every step and only does what's
  still missing — nothing about it can double-initialize or corrupt an existing install.
- **Lost the `rps_app` password**: it's in `db.properties` itself (not just the
  install-time credentials file), so this only actually matters if that file is also
  gone — in which case reset it with `psql` as the `postgres` superuser (using the
  password saved from `install-credentials.txt` at install time) and update
  `db.properties` to match.
