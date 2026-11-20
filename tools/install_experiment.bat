@echo off
setlocal
REM ============================================================
REM  Fusion Capture - one-click install for Windows (NO bash needed)
REM  Place fusion-capture.apk (and optionally screen-push.jar) in
REM  the same folder as this .bat, then just run:
REM      install_experiment.bat
REM  Or pass your receiver PC IP to also start the camera stream:
REM      install_experiment.bat 192.168.1.20
REM ============================================================

set "ADBEXE=adb"
if exist "%~dp0adb.exe" set "ADBEXE=%~dp0adb.exe"
set "HOSTIP=%~1"

echo ========== 1/5 Check device ==========
"%ADBEXE%" devices -l
"%ADBEXE%" get-state >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No adb device found.
  echo   USB  : enable USB debugging and authorize this PC.
  echo   WiFi : adb connect ^<tablet-ip^>:5555
  exit /b 1
)
echo Device model / Android version:
"%ADBEXE%" shell getprop ro.product.model
"%ADBEXE%" shell getprop ro.build.version.release
"%ADBEXE%" shell getprop ro.build.version.sdk
"%ADBEXE%" shell id

set "APKFILE="
if exist "%~dp0fusion-capture.apk" set "APKFILE=%~dp0fusion-capture.apk"
if not defined APKFILE if exist "%CD%\fusion-capture.apk" set "APKFILE=%CD%\fusion-capture.apk"
if not defined APKFILE if exist "%USERPROFILE%\Downloads\fusion-capture.apk" set "APKFILE=%USERPROFILE%\Downloads\fusion-capture.apk"
if not defined APKFILE (
  echo [ERROR] fusion-capture.apk not found.
  echo   Put it next to this .bat, in the current folder, or in Downloads.
  exit /b 1
)

echo ========== 2/5 Install APK ==========
echo Using: %APKFILE%
"%ADBEXE%" install -r "%APKFILE%"
if errorlevel 1 (
  echo [ERROR] install failed. See the message above.
  exit /b 1
)

echo ========== 3/5 Grant CAMERA ==========
"%ADBEXE%" shell pm grant com.fusion.capture android.permission.CAMERA
if errorlevel 1 echo [WARN] pm grant failed: grant CAMERA manually in Settings.
"%ADBEXE%" shell dumpsys package com.fusion.capture | findstr /C:"android.permission.CAMERA"

echo ========== 4/5 Push screen dex ==========
set "JARFILE="
if exist "%~dp0screen-push.jar" set "JARFILE=%~dp0screen-push.jar"
if not defined JARFILE if exist "%CD%\screen-push.jar" set "JARFILE=%CD%\screen-push.jar"
if not defined JARFILE if exist "%USERPROFILE%\Downloads\screen-push.jar" set "JARFILE=%USERPROFILE%\Downloads\screen-push.jar"
if defined JARFILE (
  "%ADBEXE%" push "%JARFILE%" /data/local/tmp/screen-push.jar
  "%ADBEXE%" shell chmod 644 /data/local/tmp/screen-push.jar
) else (
  echo [INFO] screen-push.jar not found, skipped. Not needed for camera-only test.
)

echo ========== 5/5 Done ==========
echo Installed com.fusion.capture
echo NOTE: there is NO launcher icon by design (headless capture service).
echo       Verify with: adb shell pm list packages ^| findstr fusion
echo.
echo --- Next: test the CAMERA stream (9115) ---
echo  1. On this PC start the receiver first:
echo       python tools\stream_probe.py listen --seconds 10
echo  2. Start capture (one single line, replace ^<PC_IP^> with this PC's LAN IP):
echo       adb shell am start-foreground-service -n com.fusion.capture/.CameraStreamService --es host ^<PC_IP^> --ei port 9115 --ei sec 0 --ei pts 1
echo  3. Watch logs:
echo       adb logcat -s FusionCamStream
echo.
echo NOTE: a tablet is NOT the glasses. The 9114 screen stream relies on
echo       shell uid + SurfaceControl virtual display, tuned for Android 9 glasses.
echo       It will likely fail on a modern Android tablet. Test 9115 first.

if not "%HOSTIP%"=="" (
  echo.
  echo ========== Starting camera stream to %HOSTIP%:9115 ==========
  "%ADBEXE%" shell am start-foreground-service -n com.fusion.capture/.CameraStreamService --es host %HOSTIP% --ei port 9115 --ei sec 0 --ei pts 1
  echo To stop: adb shell am broadcast -a com.fusion.capture.STOP_CAMERA -n com.fusion.capture/.CaptureReceiver
)

endlocal
