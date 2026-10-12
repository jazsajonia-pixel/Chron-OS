; PocketOS test fixture: minimal real NSIS installer (public domain). Installs hello_console_x64.exe.
Name "Sample Notes"
OutFile "sample_setup_nsis.exe"
InstallDir "$PROGRAMFILES64\Sample Notes"
RequestExecutionLevel user
Unicode true
SetCompressor /SOLID lzma
Page directory
Page instfiles
Section
  SetOutPath $INSTDIR
  File "hello_console_x64.exe"
  WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd
Section "Uninstall"
  Delete "$INSTDIR\hello_console_x64.exe"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
SectionEnd
