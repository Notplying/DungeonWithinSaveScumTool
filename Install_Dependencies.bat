@echo off
REM ============================================================
REM  Save Manager - one-click dependency setup (double-click me)
REM
REM  Installs everything the app needs:
REM    * Python 3 with tkinter (the app's only dependency -
REM      everything it uses is in the standard library)
REM    * Verifies the bundled adb\adb.exe
REM    * Installs requirements.txt too, if one ever appears
REM
REM  Installs per-user: no admin rights needed.
REM
REM  Self-test hooks (not for normal use):
REM    SAVE_MGR_SELFTEST=1  only runs the Python check, then
REM                         exits 0 (found) or 1 (missing).
REM ============================================================
setlocal EnableDelayedExpansion
title Save Manager - One-Click Setup
cd /d "%~dp0"

echo ======================================================
echo  Save Manager - one-click dependency setup
echo ======================================================
echo  Needed: Python 3 with tkinter. ADB is already bundled.
echo.

set "PYEXE="

call :FindPython
if defined PYEXE goto :FoundPython

REM --- Self-test mode: report only, never install anything ---
if defined SAVE_MGR_SELFTEST (
  echo [SELFTEST] No working Python 3 with tkinter found.
  exit /b 1
)

echo [..] No working Python 3 with tkinter found. Installing...
echo.

where winget >nul 2>nul
if %errorlevel%==0 goto :WingetInstall
echo [..] winget is not available, using direct download instead.
goto :DirectInstall

:WingetInstall
echo [..] Installing Python via winget (silent, per-user)...
winget install -e --id Python.Python.3 --accept-package-agreements --accept-source-agreements --override "/quiet InstallAllUsers=0 PrependPath=1 Include_tcltk=1 Include_test=0"
call :FindPython
if defined PYEXE goto :FoundPython
echo [..] winget did not produce a working Python, trying direct download...
echo.
goto :DirectInstall

:DirectInstall
set "PYVER=3.14.0"
set "PYURL=https://www.python.org/ftp/python/%PYVER%/python-%PYVER%-amd64.exe"
set "PYSETUP=%TEMP%\python-%PYVER%-amd64.exe"
echo [..] Downloading Python %PYVER% from python.org ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%PYURL%' -OutFile '%PYSETUP%' -UseBasicParsing"
if not exist "%PYSETUP%" (
  echo [FAIL] Download failed. Check your internet connection and try again.
  goto :Fail
)
echo [..] Installing Python %PYVER% (silent, per-user, no admin needed)...
"%PYSETUP%" /quiet InstallAllUsers=0 PrependPath=1 Include_tcltk=1 Include_test=0
del "%PYSETUP%" >nul 2>nul
call :FindPython
if defined PYEXE goto :FoundPython
echo [FAIL] Python installed but has no tkinter. Reinstall with "tcl/tk" enabled.
goto :Fail

:FoundPython
if defined SAVE_MGR_SELFTEST (
  echo [SELFTEST] Found working Python: %PYEXE%
  exit /b 0
)
echo [OK] Using Python: %PYEXE%
"%PYEXE%" -c "import tkinter; print('     tkinter check passed.')"

REM --- Future-proofing: install pip packages if the app ever gains any ---
if exist "%~dp0requirements.txt" (
  echo [..] Installing Python packages from requirements.txt ...
  "%PYEXE%" -m pip install -r "%~dp0requirements.txt"
  if errorlevel 1 (
    echo [FAIL] Could not install Python packages.
    goto :Fail
  )
)

call :EnsureUserPath

if not exist "%~dp0adb\adb.exe" (
  echo [FAIL] adb\adb.exe is missing from the app folder. Re-download the app.
  goto :Fail
)
"%~dp0adb\adb.exe" version >nul 2>nul
if errorlevel 1 (
  echo [FAIL] adb\adb.exe exists but does not run on this PC.
  goto :Fail
)
echo [OK] Bundled adb works.

echo.
echo ======================================================
echo  Setup complete. Start the app with Start_SaveManager.vbs
echo ======================================================
set "LAUNCH="
set /p LAUNCH="Launch Save Manager now? [Y/n] "
if /i "%LAUNCH%"=="n" goto :End
if /i "%LAUNCH%"=="no" goto :End
start "" "%~dp0Start_SaveManager.vbs"
:End
pause
exit /b 0

:Fail
echo.
echo  Setup did NOT complete. Fix the error above and run again.
pause
exit /b 1

REM ------------------------------------------------------------
REM  FindPython: sets PYEXE to a python with working tkinter,
REM  or leaves it empty. Checks PATH first, then the py launcher,
REM  then well-known install folders.
REM ------------------------------------------------------------
:FindPython
set "PYEXE="
for %%C in (python pythonw) do (
  where %%C >nul 2>nul
  if !errorlevel!==0 (
    %%C -c "import tkinter" >nul 2>nul
    if !errorlevel!==0 (
      for /f "delims=" %%X in ('where %%C') do (
        if not defined PYEXE set "PYEXE=%%X"
      )
    )
  )
  if defined PYEXE exit /b 0
)
py -3 -c "import tkinter" >nul 2>nul
if %errorlevel%==0 (
  for /f "delims=" %%X in ('py -3 -c "import sys; print(sys.executable)"') do (
    set "PYEXE=%%X"
  )
)
if defined PYEXE exit /b 0
for /d %%D in ("%LocalAppData%\Programs\Python\Python3*" "%ProgramFiles%\Python3*") do (
  if exist "%%D\python.exe" (
    "%%D\python.exe" -c "import tkinter" >nul 2>nul
    if !errorlevel!==0 set "PYEXE=%%D\python.exe"
  )
  if defined PYEXE exit /b 0
)
exit /b 0

REM ------------------------------------------------------------
REM  EnsureUserPath: adds the Python folder to the *user* PATH
REM  so future consoles find it. Uses PowerShell to avoid the
REM  1024-char truncation limit of setx.
REM ------------------------------------------------------------
:EnsureUserPath
for %%I in ("%PYEXE%") do set "PYDIR=%%~dpI"
set "PYDIR=%PYDIR:~0,-1%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p=[Environment]::GetEnvironmentVariable('Path','User'); if ($p -split ';' -notcontains '%PYDIR%') { [Environment]::SetEnvironmentVariable('Path', ($p + ';%PYDIR%;%PYDIR%\Scripts'), 'User'); Write-Host '[OK] Added Python to user PATH.' } else { Write-Host '[OK] Python already on user PATH.' }"
exit /b 0
