; ---------------------------------------------------------------------------------------
; Royal Pizza Sahowala -- Windows installer.
;
; Wraps a jpackage app-image (exe + bundled JRE + app jar) together with a bundled
; PostgreSQL 17 server and the VC++ redistributable it needs, and drives the database
; bootstrap ourselves (setup-database.ps1) rather than using jpackage's own --type exe,
; which has no hook for installing a service or doing a data-preserving uninstall.
;
; Build this from installer\build-installer.ps1, which stages every [Files] source below
; before invoking ISCC. Running ISCC directly against a stale installer\build\ will pack
; whatever happens to be there -- always go through build-installer.ps1.
; ---------------------------------------------------------------------------------------

#define AppName "Royal Pizza Sahowala"
#define AppVersion "1.0.0"
#define AppPublisher "Royal Pizza Sahowala"
#define AppExeName "RoyalPizzaSahowala.exe"
; Fixed across every version so upgrades target the same install record instead of
; creating a second, parallel entry in Programs and Features.
#define AppId "{{0292AE25-7CFB-4D09-8983-D7656BBB7E6D}"

[Setup]
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; The whole point of a hand-written Run section (service registration, initdb, ACLs) is
; that it needs an elevated process -- there is no unelevated path through this installer.
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=out
OutputBaseFilename=RoyalPizzaSahowala-Setup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#AppExeName}
SetupIconFile={#SourcePath}assets\rps-icon.ico
; No 32-bit Windows build of PostgreSQL 17 or the JDK 25 runtime exists -- ArchitecturesAllowed
; already refuses 32-bit Windows outright, so there is no separate message to author here.

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
; The jpackage app-image: RoyalPizzaSahowala.exe + app\ (jar + libs) + runtime\ (jlink JRE).
; jpackage's --name has no spaces, so its output folder is "RoyalPizzaSahowala", not
; {#AppName} (the spaced display name used everywhere else in this script).
Source: "build\jpackage-out\RoyalPizzaSahowala\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs
; PostgreSQL server binaries (bin, lib, share) -- pgAdmin and its GUI DLLs already excluded
; by fetch-dependencies.ps1, so this is server-only.
Source: "third-party\pgsql\*"; DestDir: "{app}\pgsql"; Flags: recursesubdirs createallsubdirs
; Install/uninstall/backup scripts, run by [Run]/[UninstallRun] below and from the
; "Backup Database" shortcut.
Source: "scripts\setup-database.ps1"; DestDir: "{app}\scripts"
Source: "scripts\remove-database.ps1"; DestDir: "{app}\scripts"
Source: "scripts\backup-database.ps1"; DestDir: "{app}\scripts"
; VC++ redistributable -- staged to a temp dir, not installed permanently under {app};
; PostgreSQL links against it but it is a shared system component, not part of this app.
Source: "third-party\vc_redist.x64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"
Name: "{group}\Backup Database"; Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Normal -File ""{app}\scripts\backup-database.ps1"" -InstallDir ""{app}"""; \
    IconFilename: "{app}\{#AppExeName}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Run]
; Silent, idempotent -- no-ops if an equal or newer VC++ runtime is already present.
Filename: "{tmp}\vc_redist.x64.exe"; Parameters: "/install /quiet /norestart"; \
    StatusMsg: "Installing supporting runtime components..."; Flags: waituntilterminated
; The real database bootstrap: initdb (fresh install) or a no-op past that point (upgrade),
; service registration/start, credentials, schema migrations, seed verification. Every
; step is idempotent, so re-running this on a retried/interrupted install is always safe.
Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\setup-database.ps1"" -InstallDir ""{app}"""; \
    StatusMsg: "Setting up the database (this can take a minute on first install)..."; \
    Flags: waituntilterminated

[UninstallRun]
; DeleteRPSData is a Pascal global set by the confirmation prompts in [Code] below --
; ticked-by-default-off, because the ordinary uninstall path must never touch business
; data. Only runs remove-database.ps1 with -DeleteData when the operator explicitly
; confirmed data removal twice.
Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\remove-database.ps1"" -InstallDir ""{app}"" -DeleteData"; \
    StatusMsg: "Removing the database and business data..."; \
    Flags: waituntilterminated; Check: DeleteRPSDataConfirmed; RunOnceId: "RemoveDbWithData"
Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\remove-database.ps1"" -InstallDir ""{app}"""; \
    StatusMsg: "Stopping the database service..."; \
    Flags: waituntilterminated; Check: not DeleteRPSDataConfirmed; RunOnceId: "RemoveDbKeepData"

[Code]
var
  DeleteRPSData: Boolean;

function DeleteRPSDataConfirmed(): Boolean;
begin
  Result := DeleteRPSData;
end;

function InitializeUninstall(): Boolean;
var
  FirstConfirm, SecondConfirm: Integer;
begin
  Result := True;
  DeleteRPSData := False;

  FirstConfirm := MsgBox(
    'Uninstalling Royal Pizza Sahowala will remove the application, but the database ' +
    '(all orders, menu and settings) is kept by default so you can reinstall later ' +
    'without losing anything.' + #13#10#13#10 +
    'Do you also want to permanently delete the database and all business data?',
    mbConfirmation, MB_YESNO or MB_DEFBUTTON2);

  if FirstConfirm = IDYES then
  begin
    SecondConfirm := MsgBox(
      'This cannot be undone: every order, menu item and staff account will be ' +
      'permanently deleted.' + #13#10#13#10 +
      'Are you absolutely sure you want to delete all business data?',
      mbError, MB_YESNO or MB_DEFBUTTON2);
    DeleteRPSData := (SecondConfirm = IDYES);
  end;
end;
