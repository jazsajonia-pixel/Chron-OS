; PocketOS test fixture: NSIS installer that creates Start Menu and Desktop shortcuts (public domain).
; Installs hello_gui_x64.exe as "Sample Paint", like typical real-world installers do.
Name "Sample Paint"
OutFile "sample_setup_shortcuts.exe"
InstallDir "$PROGRAMFILES64\Sample Paint"
RequestExecutionLevel user
Unicode true
SetCompressor /SOLID lzma
Page directory
Page instfiles
Section
  SetOutPath $INSTDIR
  File /oname=SamplePaint.exe "hello_gui_x64.exe"
  WriteUninstaller "$INSTDIR\uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Sample Paint"
  CreateShortcut "$SMPROGRAMS\Sample Paint\Sample Paint.lnk" "$INSTDIR\SamplePaint.exe"
  CreateShortcut "$SMPROGRAMS\Sample Paint\Uninstall Sample Paint.lnk" "$INSTDIR\uninstall.exe"
  CreateShortcut "$DESKTOP\Sample Paint.lnk" "$INSTDIR\SamplePaint.exe"
SectionEnd
Section "Uninstall"
  Delete "$INSTDIR\SamplePaint.exe"
  Delete "$INSTDIR\uninstall.exe"
  Delete "$SMPROGRAMS\Sample Paint\*.lnk"
  RMDir "$SMPROGRAMS\Sample Paint"
  Delete "$DESKTOP\Sample Paint.lnk"
  RMDir "$INSTDIR"
SectionEnd
