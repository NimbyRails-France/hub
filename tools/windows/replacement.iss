// Run on EVERY installation, including same-version repairs and missing files.
// The program directory is disposable. Profiles/logs/projects are external.
// Snapshot via same-volume renames, then restore on failure. Inno exclusively
// manages its uninsNNN.* control files; these cannot be moved while open.
var
  ProgramRoot, BackupRoot: String;
  ReplacementStarted, ReplacementCommitted: Boolean;

#include "diagnostics.iss"

function IsControlFile(const Name: String): Boolean;
var N, Ext: String; I: Integer;
begin
  N := Lowercase(Name);
  Ext := ExtractFileExt(N);
  Result := False;
  if (Length(N) <> 12) or (Copy(N, 1, 5) <> 'unins') then exit;
  for I := 6 to 8 do if (N[I] < '0') or (N[I] > '9') then exit;
  Result := (Ext = '.exe') or (Ext = '.dat') or (Ext = '.msg');
end;

procedure Note(const Message: String);
var Folder: String;
begin
  Log('NRF replacement: ' + Message);
  Folder := ExpandConstant('{localappdata}\NimbyRailsFrance\logs\hub');
  if ForceDirectories(Folder) then
    SaveStringToFile(Folder + '\installer-migration.log',
      Utf8Encode(GetDateTimeString('yyyy-mm-dd hh:nn:ss', '-', ':') + ' ' + Message + #13#10), True);
end;

procedure NoLinks(const Path: String);
var Item: TFindRec;
begin
  if FindFirst(Path, Item) then begin
    try
      if (Item.Attributes and FILE_ATTRIBUTE_REPARSE_POINT) <> 0 then
        RaiseException(FmtMessage(CustomMessage('LinkedPath'), [Path]));
    finally FindClose(Item); end;
  end;
end;

procedure CheckTree(const Path: String);
var Item: TFindRec;
begin
  NoLinks(Path);
  if not DirExists(Path) then exit;
  if FindFirst(Path + '\*', Item) then begin
    try
      repeat
        if (Item.Name <> '.') and (Item.Name <> '..') then
          CheckTree(Path + '\' + Item.Name);
      until not FindNext(Item);
    finally FindClose(Item); end;
  end;
end;

function Entries(const Folder: String; KeepControl: Boolean): TStringList;
var Item: TFindRec;
begin
  Result := TStringList.Create;
  if FindFirst(Folder + '\*', Item) then begin
    try
      repeat
        if (Item.Name <> '.') and (Item.Name <> '..') then
          if not KeepControl or not IsControlFile(Item.Name) then Result.Add(Item.Name);
      until not FindNext(Item);
    finally FindClose(Item); end;
  end;
end;

procedure MoveEntry(const Source, Destination: String);
begin
  if not RenameFile(Source, Destination) then
    RaiseException(FmtMessage(CustomMessage('MoveFailed'), [Source]));
  Note('Moved ' + Source + ' -> ' + Destination);
end;

procedure DeleteBackup;
begin
  // Never erase a path supplied by a journal: use the checked fixed sibling.
  CheckTree(BackupRoot);
  if not DelTree(BackupRoot, True, True, True) then
    RaiseException(FmtMessage(CustomMessage('CleanupFailed'), [BackupRoot]));
end;

procedure RestoreBackup;
var Items: TStringList; I: Integer; Phase: String;
begin
  CheckTree(ProgramRoot);
  CheckTree(BackupRoot);
  Phase := GetIniString('transaction', 'phase', '', BackupRoot + '\transaction.ini');
  if (Phase <> 'snapshot') and (Phase <> 'ready') then
    RaiseException(FmtMessage(CustomMessage('RecoveryState'), [BackupRoot]));
  ForceDirectories(ProgramRoot);
  if Phase = 'ready' then begin
    // Quarantine partial new files. A snapshot-phase restore only puts back
    // already moved old entries, leaving not-yet-moved old files intact.
    Items := Entries(ProgramRoot, True);
    try
      ForceDirectories(BackupRoot + '\failed');
      for I := 0 to Items.Count - 1 do
        MoveEntry(ProgramRoot + '\' + Items[I], BackupRoot + '\failed\' + Items[I]);
    finally Items.Free; end;
    if not SetIniString('transaction', 'phase', 'snapshot', BackupRoot + '\transaction.ini') then
      RaiseException(CustomMessage('RecoveryLog'));
  end;
  Items := Entries(BackupRoot + '\payload', False);
  try
    for I := 0 to Items.Count - 1 do
      MoveEntry(BackupRoot + '\payload\' + Items[I], ProgramRoot + '\' + Items[I]);
  finally Items.Free; end;
  Note('Previous program restored: ' + ProgramRoot);
  DeleteBackup;
end;

procedure ValidateDestination;
var Ancestor, Parent, Registered, DataRoot: String; Items: TStringList; Known: Boolean;
begin
  ProgramRoot := RemoveBackslashUnlessRoot(ExpandFileName(ExpandConstant('{app}')));
  BackupRoot := ProgramRoot + '.nrf-rollback';
  if Length(ProgramRoot) < 10 then RaiseException(CustomMessage('BroadDirectory'));
  DataRoot := Lowercase(ExpandConstant('{localappdata}\NimbyRailsFrance'));
  if (Pos(Lowercase(ProgramRoot) + '\', DataRoot + '\') = 1) or
     (Pos(DataRoot + '\', Lowercase(ProgramRoot) + '\') = 1) then
    RaiseException(CustomMessage('SeparateData'));
  Ancestor := ProgramRoot;
  while Length(Ancestor) > 3 do begin
    NoLinks(Ancestor);
    Parent := ExtractFileDir(Ancestor);
    if Parent = Ancestor then break;
    Ancestor := Parent;
  end;
  CheckTree(ProgramRoot);
  CheckTree(BackupRoot);
  Known := CompareText(ProgramRoot, ExpandConstant('{localappdata}\Programs\NimbyRailsFranceHub')) = 0;
  if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{53CA0228-8196-4BF5-B77A-49CB07F9F469}_is1', 'Inno Setup: App Path', Registered) then
    Known := Known or (CompareText(ProgramRoot, RemoveBackslashUnlessRoot(Registered)) = 0);
  Known := Known or (GetIniString('hub', 'product', '', ProgramRoot + '\.nrfhub-install.ini') = 'NRFHub');
  Items := Entries(ProgramRoot, False);
  try
    if (Items.Count > 0) and not Known then
      RaiseException(CustomMessage('UnknownDirectory'));
  finally Items.Free; end;
  if DirExists(BackupRoot) then
    if CompareText(GetIniString('transaction', 'target', '', BackupRoot + '\transaction.ini'), ProgramRoot) <> 0 then
      RaiseException(FmtMessage(CustomMessage('BackupLog'), [BackupRoot]));
end;

procedure BeginReplacement;
var Items: TStringList; I: Integer; Journal: String; FreeMB, TotalMB: Cardinal;
begin
  InstallerCheckpoint('validating destination');
  Note('Installer version={#Version}; target=' + ExpandConstant('{app}'));
  if GetSpaceOnDisk(ExpandConstant('{app}'), True, FreeMB, TotalMB) then
    Note('Target disk free MB=' + IntToStr(FreeMB) + '; total MB=' + IntToStr(TotalMB));
  ValidateDestination;
  if DirExists(BackupRoot) then begin
    if GetIniString('transaction', 'phase', '', BackupRoot + '\transaction.ini') = 'committed' then
      DeleteBackup
    else RestoreBackup;
  end;
  if not ForceDirectories(BackupRoot + '\payload') then RaiseException(CustomMessage('BackupFailed'));
  Journal := BackupRoot + '\transaction.ini';
  if not SetIniString('transaction', 'target', ProgramRoot, Journal) or
     not SetIniString('transaction', 'phase', 'snapshot', Journal) then
    RaiseException(CustomMessage('TransactionLog'));
  ReplacementStarted := True;
  InstallerCheckpoint('backing up previous installation');
  Items := Entries(ProgramRoot, True);
  try
    for I := 0 to Items.Count - 1 do
      MoveEntry(ProgramRoot + '\' + Items[I], BackupRoot + '\payload\' + Items[I]);
  finally Items.Free; end;
  if not SetIniString('transaction', 'phase', 'ready', Journal) then
    RaiseException(CustomMessage('TransactionLog'));
  Note('Clean program destination ready: ' + ProgramRoot);
  InstallerCheckpoint('copying program files');
end;

procedure CurStepChanged(CurStep: TSetupStep);
var LauncherPresent, ConfigPresent, RuntimePresent: Boolean;
begin
  if CurStep = ssInstall then begin
    // ssInstall is after Inno's running-application handling, before copying.
    try
      BeginReplacement;
#ifdef ReplacementFailureTest
      // Compiled only into the private CI fault-injection installer.
      RaiseException('Injected CI failure after snapshot');
#endif
    except
      Note(GetExceptionMessage);
      SuppressibleMsgBox(GetExceptionMessage, mbError, MB_OK, IDOK);
      Abort;
    end;
  end;
  if CurStep = ssPostInstall then begin
    InstallerCheckpoint('checking installed components');
    LauncherPresent := LogInstalledComponent('NRFHub.exe');
    ConfigPresent := LogInstalledComponent('app\NRFHub.cfg');
    RuntimePresent := LogInstalledComponent('runtime\bin\server\jvm.dll');
    if not LauncherPresent or not ConfigPresent or not RuntimePresent then begin
      Note(CustomMessage('MissingComponents'));
      SnapshotInstallerLog;
      RaiseException(CustomMessage('MissingComponents'));
    end;
    if not SetIniString('hub', 'product', 'NRFHub', ProgramRoot + '\.nrfhub-install.ini') or
       not SetIniString('transaction', 'phase', 'committed', BackupRoot + '\transaction.ini') then begin
      Note(CustomMessage('CommitFailed'));
      RaiseException(CustomMessage('CommitFailed'));
    end;
    ReplacementCommitted := True;
    Note('Replacement committed; user profile preserved: ' + ProgramRoot);
    InstallerCheckpoint('committed');
  end;
  if (CurStep = ssDone) and ReplacementCommitted then begin
    try DeleteBackup;
    except Note(GetExceptionMessage); end;
  end;
end;

procedure DeinitializeSetup;
begin
  Note('Setup ending; version={#Version}; last stage=' + InstallerStage);
  SnapshotInstallerLog;
  if ReplacementStarted and not ReplacementCommitted then begin
    Note('Installation not committed; restoring previous files. Detailed error log: ' + InstallerLogPath);
    try RestoreBackup;
    except Note('Restore incomplete; next installer will resume: ' + GetExceptionMessage); end;
  end;
  if ReplacementCommitted then Log('NRF diagnostics: outcome=committed')
  else Log('NRF diagnostics: outcome=not committed (failure or cancellation)');
  SnapshotInstallerLog;
end;
