#ifndef Stage
 #error Stage required
#endif
[Setup]
AppId={{53CA0228-8196-4BF5-B77A-49CB07F9F469}
AppName=NimbyRails France Hub
AppVersion=0.2.0
DefaultDirName={localappdata}\Programs\NimbyRailsFranceHub
DefaultGroupName=NimbyRails France
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#Output}
OutputBaseFilename=NRFHub-0.2.0-Setup
Compression=lzma2
SolidCompression=yes
UninstallDisplayIcon={app}\NRFHub.exe
CloseApplications=yes
CloseApplicationsFilter=NRFHub.exe
RestartApplications=no
SetupMutex=NRFHubInstaller
WizardStyle=modern
[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
[Files]
Source: "{#Stage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\NRF Hub"; Filename: "{app}\NRFHub.exe"
[Run]
Filename: "{app}\NRFHub.exe"; Description: "Lancer NRF Hub"; Flags: nowait postinstall skipifsilent
Filename: "{app}\NRFHub.exe"; Flags: nowait; Check: RelaunchRequested
[Code]
function RelaunchRequested: Boolean;
var I: Integer;
begin
 Result := False;
 if not WizardSilent then exit;
 for I := 1 to ParamCount do
  if CompareText(ParamStr(I), '/RELAUNCH') = 0 then Result := True;
end;
