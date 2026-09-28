// Keep Inno's own error messages beside the Hub logs, including failed installs.
// Snapshots preserve the raw log bytes (and their encoding) while Inno still has
// the source file open. Diagnostics must never prevent installation or recovery.
var
  InstallerLogPath, InstallerStage: String;
  LastLoggedProgress: Integer;

function InstallerLogDirectory: String;
begin
  Result := ExpandConstant('{localappdata}\NimbyRailsFrance\logs\hub');
end;

procedure SnapshotInstallerLog;
var Source, Folder: String; Contents: AnsiString;
begin
  try
    Source := ExpandConstant('{log}');
    Folder := InstallerLogDirectory;
    if (Source = '') or (InstallerLogPath = '') then exit;
    if not ForceDirectories(Folder) then exit;
    if LoadStringFromLockedFile(Source, Contents) then begin
      if not SaveStringToFile(InstallerLogPath, Contents, False) then
        Log('NRF diagnostics: cannot save attempt log ' + InstallerLogPath);
      // A caller may already have chosen this destination through /LOG.
      if CompareText(Source, Folder + '\installer-latest.log') <> 0 then
        if not SaveStringToFile(Folder + '\installer-latest.log', Contents, False) then
          Log('NRF diagnostics: cannot save installer-latest.log');
    end else Log('NRF diagnostics: cannot read active Inno log ' + Source);
  except
    Log('NRF diagnostics: snapshot failed: ' + GetExceptionMessage);
  end;
end;

procedure InstallerCheckpoint(const Stage: String);
begin
  InstallerStage := Stage;
  Log('NRF diagnostics: stage=' + Stage);
  SnapshotInstallerLog;
end;

function InitializeSetup: Boolean;
var Base: String; Index: Integer;
begin
  Result := True;
  LastLoggedProgress := -10;
  InstallerStage := 'initialization';
  try
    if ForceDirectories(InstallerLogDirectory) then begin
      Base := InstallerLogDirectory + '\installer-{#Version}-' +
        GetDateTimeString('yyyymmdd-hhnnss', '-', ':');
      InstallerLogPath := Base + '.log';
      Index := 0;
      while FileExists(InstallerLogPath) do begin
        Index := Index + 1;
        InstallerLogPath := Base + '-' + IntToStr(Index) + '.log';
      end;
    end;
    Log('NRF diagnostics: Hub installer version={#Version}');
    Log('NRF diagnostics: Windows=' + GetWindowsVersionString +
      ' architecture=' + GetEnv('PROCESSOR_ARCHITECTURE'));
    Log('NRF diagnostics: installer=' + ExpandConstant('{srcexe}'));
    Log('NRF diagnostics: original Inno log=' + ExpandConstant('{log}'));
    Log('NRF diagnostics: persistent attempt log=' + InstallerLogPath);
    InstallerCheckpoint('initialization');
  except
    Log('NRF diagnostics: initialization failed: ' + GetExceptionMessage);
  end;
end;

procedure InitializeWizard;
begin
  InstallerCheckpoint('wizard');
end;

procedure CancelButtonClick(CurPageID: Integer; var Cancel, Confirm: Boolean);
begin
  Log('NRF diagnostics: cancellation requested on page=' + IntToStr(CurPageID));
  SnapshotInstallerLog;
end;

procedure CurInstallProgressChanged(CurProgress, MaxProgress: Integer);
var Percent: Integer;
begin
  if MaxProgress <= 0 then exit;
  Percent := Round((CurProgress / MaxProgress) * 100);
  if Percent >= LastLoggedProgress + 10 then begin
    LastLoggedProgress := Percent;
    Log('NRF diagnostics: installation progress=' + IntToStr(Percent) + '%');
    SnapshotInstallerLog;
  end;
end;

function LogInstalledComponent(const RelativePath: String): Boolean;
var Name, Version: String; Size: Int64;
begin
  Name := ProgramRoot + '\' + RelativePath;
  Result := FileExists(Name);
  if not Result then Log('NRF diagnostics: MISSING component=' + Name)
  else begin
    if FileSize64(Name, Size) then
      Log('NRF diagnostics: component=' + Name + ' bytes=' + IntToStr(Size));
    if GetVersionNumbersString(Name, Version) then
      Log('NRF diagnostics: component version=' + Version);
  end;
end;
