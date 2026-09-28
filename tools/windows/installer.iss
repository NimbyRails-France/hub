#ifndef Stage
 #error Stage required
#endif
#ifndef Version
 #error Version required
#endif
#ifndef NativeVersion
 #error NativeVersion required
#endif
#ifndef HubIcon
 #define HubIcon SourcePath + "..\..\src\desktopMain\resources\branding\hub.ico"
#endif
[Setup]
AppId={{53CA0228-8196-4BF5-B77A-49CB07F9F469}
AppName=NimbyRails France Hub
AppVersion={#Version}
VersionInfoVersion={#NativeVersion}.0
AppPublisher=NimbyRails France
VersionInfoCompany=NimbyRails France
VersionInfoDescription=NimbyRails France Hub Setup
VersionInfoProductName=NimbyRails France Hub
AppPublisherURL=https://github.com/NimbyRails-France/hub
DefaultDirName={localappdata}\Programs\NimbyRailsFranceHub
DefaultGroupName=NimbyRails France
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#Output}
OutputBaseFilename=NRFHub-{#Version}-windows-x64-Setup
Compression=lzma2
SolidCompression=yes
UninstallDisplayIcon={app}\app\NRFHub.ico
SetupIconFile={#HubIcon}
CloseApplications=yes
CloseApplicationsFilter=NRFHub.exe
RestartApplications=no
SetupMutex=NRFHubInstaller
WizardStyle=modern
SetupLogging=yes
[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
[CustomMessages]
english.LaunchHub=Launch NRF Hub
french.LaunchHub=Lancer NRF Hub
#include "languages.iss"
[Files]
Source: "{#Stage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Also brand shortcuts for CI images that use the stock JDK launcher.
Source: "{#HubIcon}"; DestDir: "{app}\app"; DestName: "NRFHub.ico"; Flags: ignoreversion; AfterInstall: ValidateInstalledComponents
[Icons]
Name: "{group}\NRF Hub"; Filename: "{app}\NRFHub.exe"; IconFilename: "{app}\app\NRFHub.ico"
[Run]
Filename: "{app}\NRFHub.exe"; Description: "{cm:LaunchHub}"; Flags: nowait postinstall skipifsilent; Check: HubInstallationReady
Filename: "{app}\NRFHub.exe"; Flags: nowait; Check: RelaunchRequested
[Code]
#include "replacement.iss"
function RelaunchRequested: Boolean;
var I: Integer;
begin
 Result := False;
 if not WizardSilent or not HubInstallationReady then exit;
 for I := 1 to ParamCount do
  if CompareText(ParamStr(I), '/RELAUNCH') = 0 then Result := True;
end;
