#ifndef AppVersion
  #define AppVersion "1.0.28"
#endif

[Setup]
AppId={{8E94A2C7-4D1F-4B9E-8F1C-92C51B7E2026}
AppName=Help-CSL
AppVersion={#AppVersion}
AppVerName=Help-CSL {#AppVersion}
AppPublisher=CSL TechSupport
DefaultDirName={autopf}\Help-CSL
DefaultGroupName=Help-CSL
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=output
OutputBaseFilename=setup
SetupIconFile=help-csl.ico
UninstallDisplayIcon={app}\Help-CSL.exe
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=force

[Languages]
#ifdef UseRussianLang
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
#endif
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык Help-CSL на Рабочем столе"; GroupDescription: "Дополнительные значки:"
Name: "autostart"; Description: "Запускать Help-CSL автоматически при входе в Windows"; GroupDescription: "Автозапуск:"

[Files]
Source: "..\build\compose\binaries\main\app\Help-CSL\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Help-CSL"; Filename: "{app}\Help-CSL.exe"
Name: "{autodesktop}\Help-CSL"; Filename: "{app}\Help-CSL.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Help-CSL"; ValueData: """{app}\Help-CSL.exe"" --minimized"; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\Help-CSL.exe"; Description: "Запустить Help-CSL"; Flags: nowait postinstall skipifsilent
