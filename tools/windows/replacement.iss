// Run on EVERY installation, including same-version repairs and missing files.
// The program directory is disposable. Profiles/logs/projects are external.
// Snapshot via same-volume renames, then restore on failure. Inno exclusively
// manages its uninsNNN.* control files; these cannot be moved while open.
var
  ProgramRoot, BackupRoot: String;
  ReplacementStarted, ReplacementCommitted: Boolean;

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
      GetDateTimeString('yyyy-mm-dd hh:nn:ss', '-', ':') + ' ' + Message + #13#10, True);
end;

procedure NoLinks(const Path: String);
var Item: TFindRec;
begin
  if FindFirst(Path, Item) then begin
    try
      if (Item.Attributes and FILE_ATTRIBUTE_REPARSE_POINT) <> 0 then
        RaiseException('Dossier ou fichier lie interdit : ' + Path);
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
    RaiseException('Impossible de deplacer ' + Source + '. Fermez le Hub et recommencez.');
  Note('Moved ' + Source + ' -> ' + Destination);
end;

procedure DeleteBackup;
begin
  // Never erase a path supplied by a journal: use the checked fixed sibling.
  CheckTree(BackupRoot);
  if not DelTree(BackupRoot, True, True, True) then
    RaiseException('Sauvegarde conservee, nettoyage impossible : ' + BackupRoot);
end;

procedure RestoreBackup;
var Items: TStringList; I: Integer; Phase: String;
begin
  CheckTree(ProgramRoot);
  CheckTree(BackupRoot);
  Phase := GetIniString('transaction', 'phase', '', BackupRoot + '\transaction.ini');
  if (Phase <> 'snapshot') and (Phase <> 'ready') then
    RaiseException('Etat de restauration inconnu : ' + BackupRoot);
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
      RaiseException('Journal de restauration inaccessible');
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
  if Length(ProgramRoot) < 10 then RaiseException('Dossier installation trop general');
  DataRoot := Lowercase(ExpandConstant('{localappdata}\NimbyRailsFrance'));
  if (Pos(Lowercase(ProgramRoot) + '\', DataRoot + '\') = 1) or
     (Pos(DataRoot + '\', Lowercase(ProgramRoot) + '\') = 1) then
    RaiseException('Le dossier programme doit etre separe des donnees utilisateur');
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
      RaiseException('Ce dossier non vide ne correspond pas a une installation NRF Hub reconnue.');
  finally Items.Free; end;
  if DirExists(BackupRoot) then
    if CompareText(GetIniString('transaction', 'target', '', BackupRoot + '\transaction.ini'), ProgramRoot) <> 0 then
      RaiseException('Sauvegarde sans journal valide : ' + BackupRoot);
end;

procedure BeginReplacement;
var Items: TStringList; I: Integer; Journal: String;
begin
  ValidateDestination;
  if DirExists(BackupRoot) then begin
    if GetIniString('transaction', 'phase', '', BackupRoot + '\transaction.ini') = 'committed' then
      DeleteBackup
    else RestoreBackup;
  end;
  if not ForceDirectories(BackupRoot + '\payload') then RaiseException('Sauvegarde impossible');
  Journal := BackupRoot + '\transaction.ini';
  if not SetIniString('transaction', 'target', ProgramRoot, Journal) or
     not SetIniString('transaction', 'phase', 'snapshot', Journal) then
    RaiseException('Journal de transaction inaccessible');
  ReplacementStarted := True;
  Items := Entries(ProgramRoot, True);
  try
    for I := 0 to Items.Count - 1 do
      MoveEntry(ProgramRoot + '\' + Items[I], BackupRoot + '\payload\' + Items[I]);
  finally Items.Free; end;
  if not SetIniString('transaction', 'phase', 'ready', Journal) then
    RaiseException('Journal de transaction inaccessible');
  Note('Clean program destination ready: ' + ProgramRoot);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then begin
    // ssInstall is after Inno's running-application handling, before copying.
    try BeginReplacement;
    except
      Note(GetExceptionMessage);
      SuppressibleMsgBox(GetExceptionMessage, mbError, MB_OK, IDOK);
      Abort;
    end;
  end;
  if CurStep = ssPostInstall then begin
    if not FileExists(ProgramRoot + '\NRFHub.exe') or
       not FileExists(ProgramRoot + '\app\NRFHub.cfg') or
       not FileExists(ProgramRoot + '\runtime\bin\server\jvm.dll') then
      RaiseException('Installation incomplete : composants principaux absents');
    if not SetIniString('hub', 'product', 'NRFHub', ProgramRoot + '\.nrfhub-install.ini') or
       not SetIniString('transaction', 'phase', 'committed', BackupRoot + '\transaction.ini') then
      RaiseException('Validation de installation impossible');
    ReplacementCommitted := True;
    Note('Replacement committed; user profile preserved: ' + ProgramRoot);
  end;
  if (CurStep = ssDone) and ReplacementCommitted then begin
    try DeleteBackup;
    except Note(GetExceptionMessage); end;
  end;
end;

procedure DeinitializeSetup;
begin
  if ReplacementStarted and not ReplacementCommitted then begin
    try RestoreBackup;
    except Note('Restore incomplete; next installer will resume: ' + GetExceptionMessage); end;
  end;
end;
