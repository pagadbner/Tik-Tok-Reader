#define AppVersion "0.13.15"
[Setup]
AppId={{7C1B4E21-65F0-4B79-A6B7-9A8A3F9A13D2}
AppName=Tik Tok Reader
AppVersion={#AppVersion}
AppPublisher=Tik Tok Reader
DefaultDirName={autopf}\\Tik Tok Reader
DefaultGroupName=Tik Tok Reader
OutputDir=..\build\installer
OutputBaseFilename=TikTokReader-Windows-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\\src\\main\\resources\\tik-tok-reader-icon.ico
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64
CloseApplications=yes
RestartApplications=no
UninstallDisplayIcon={app}\\Tik Tok Reader.exe

[Files]
Source: "..\build\compose\binaries\main\msi\Tik Tok Reader-{#AppVersion}.msi"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Run]
Filename: "msiexec.exe"; Parameters: "/i ""{tmp}\\Tik Tok Reader-{#AppVersion}.msi"" /qn /norestart"; StatusMsg: "Instalando Tik Tok Reader para Windows…"; Flags: waituntilterminated

Filename: "{app}\\Tik Tok Reader.exe"; Flags: nowait postinstall
