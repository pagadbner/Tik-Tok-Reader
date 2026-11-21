#define AppVersion "0.13.15"
[Setup]
AppId={{7C1B4E21-65F0-4B79-A6B7-9A8A3F9A13D2}
AppName=TikLBot
AppVersion={#AppVersion}
AppPublisher=TikLBot
DefaultDirName={autopf}\TikLBot
DefaultGroupName=TikLBot
OutputDir=..\build\installer
OutputBaseFilename=TikLBot-Windows-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64
CloseApplications=yes
RestartApplications=no
UninstallDisplayIcon={app}\TikLBot.exe

[Files]
Source: "..\build\compose\binaries\main\msi\TikLBot-{#AppVersion}.msi"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Run]
Filename: "msiexec.exe"; Parameters: "/i ""{tmp}\TikLBot-{#AppVersion}.msi"" /qn /norestart"; StatusMsg: "Instalando TikLBot para Windows…"; Flags: waituntilterminated
