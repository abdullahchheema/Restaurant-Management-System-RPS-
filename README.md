# Royal Pizza Sahowala — Restaurant Management System

A Java desktop point-of-sale system for Royal Pizza Sahowala: taking orders at the till,
printing kitchen tickets and customer receipts on a thermal roll, tracking delivery runs,
and reporting on the day's takings. Swing front end, PostgreSQL behind it.

## Requirements

- **JDK 25** (the build and run scripts look for `C:\Program Files\Java\jdk-25.0.4.1`)
- **PostgreSQL 17**, running locally
- Windows — the receipt printing and `pg_dump` discovery paths are Windows-specific

## Setup

1. Create a database and a login role for the app to use.
2. Copy `db.properties.example` to `db.properties` and fill in the real values.
   `db.properties` is gitignored and must stay that way — it holds a live credential.
3. Run the app. The schema builds itself: `Migrations.applyAll` creates every table and
   seeds the real menu on first start, and is a no-op on every start after that.

```powershell
.\build.ps1    # compile src/ into bin/
.\run.ps1      # launch the app
```

The first sign-in is staff ID `1`, password `admin123`. The app forces that password to be
changed before it will open the till — the default is published here, so it protects nothing.

## Layout

```
src/rps/
  app/        entry point and the authenticated Session
  db/         Db (connection handling), schema Migrations, and one DAO per area
  model/      order/menu/staff records; OrderDraft is the in-progress order at the till
  print/      receipt + kitchen ticket rendering and silent thermal printing
  ui/         Swing screens — PosPanel is the till, DashboardPanel the day's orders
  util/       Money, validators, password hashing, CSV export
qa/           the test suites (see below)
lib/          PostgreSQL JDBC driver, FlatLaf look-and-feel
```

Two design rules run through the data layer and are worth knowing before changing it:

- **Money is never computed in Java and then stored.** Totals, discounts and the delivery
  fee are rolled up in a single SQL statement (`OrderDao.rollupTotals`) so the database's
  exact-equality CHECK on `total` cannot be tripped by a Java/SQL rounding disagreement.
- **Prices are re-read server-side at confirm time.** What the till sends is treated as a
  selection, never as a price.

## Tests

There is no JUnit; the suites are plain `main()` classes under `qa/`. They run against a
separate **`rms_test`** database configured by `qa/run/db.properties` — never the live one.
They create, mutate and delete real rows and kill connections mid-transaction, so check the
`Using database config:` line the app prints on startup before trusting a run.

```powershell
cd qa\run
java -cp "..\..\bin;..\bin;..\..\lib\*" qa.GoldenSuite
```

| Suite | Covers |
|---|---|
| `GoldenSuite` | orders, discounts, rounding, receipts, status rules, delivery runs |
| `ConcurrencySuite` | simultaneous orders, concurrent payments, cancel/pay races |
| `StressSuite` | connection loss mid-transaction, rollback, leak checks |
| `R1Repro` | draft mutation racing a save |
| `UiSuite` | every panel builds, lays out and paints, for both roles |
| `FreshInstallSuite` | empty database → migrations → login → first order |

`FreshInstallSuite` refuses to run against a database that already holds orders, so it
cannot be pointed at the live till by accident.

## Operations

- **Receipts** are written to `Documents\RoyalPizzaSahowala\receipts\` and printed silently
  to the configured thermal printer. Paper width (80mm/58mm) is set in Settings.
- **Backups** run via `pg_dump` and upload to Backblaze B2. Offsite backup stays off until
  B2 keys are set; the app degrades quietly without them.
- **`secure-database.ps1`** is the one-time hardening script: it renames the database,
  creates a non-superuser role for the app to connect as, and rotates the superuser
  password. It takes a backup first and refuses to run while anything is connected.
