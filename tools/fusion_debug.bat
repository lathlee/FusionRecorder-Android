@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM  Fusion Capture - install + start debug streams (Windows)
REM
REM  Put adb.exe, fusion-capture.apk and screen-push.jar in this
REM  folder (or in Downloads), then run:
REM
REM    fusion_debug.bat                  -> install only, no streams
REM    fusion_debug.bat <receiver-ip>    -> install + start BOTH streams
REM                                         to <receiver-ip>:9114/9115
REM
REM  <receiver-ip> = the device running the receiver:
REM    - iPhone on the same hotspot -> iPhone LAN IP (e.g. 172.20.10.1)
REM    - PC running stream_probe.py -> this PC's LAN IP
REM ============================================================

set "ADBEXE=adb"
if exist "%~dp0adb.exe" set "ADBEXE=%~dp0adb.exe"
set "RECVIP=%~1"

echo ============================================================
echo  Fusion Capture - install + debug
echo ============================================================

echo.
echo [1/6] Device check
"%ADBEXE%" devices -l
"%ADBEXE%" get-state >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No adb device. Connect glasses via USB/WiFi and authorize.
  exit /b 1
)
echo Model:
"%ADBEXE%" shell getprop ro.product.model
echo Android / SDK:
"%ADBEXE%" shell getprop ro.build.version.release
"%ADBEXE%" shell getprop ro.build.version.sdk
echo Identity - MUST be uid=2000 for screen capture:
"%ADBEXE%" shell id

echo.
echo [2/6] Install fusion-capture.apk
set "APKFILE="
if exist "%~dp0fusion-capture.apk" set "APKFILE=%~dp0fusion-capture.apk"
if not defined APKFILE if exist "%CD%\fusion-capture.apk" set "APKFILE=%CD%\fusion-capture.apk"
if not defined APKFILE if exist "%USERPROFILE%\Downloads\fusion-capture.apk" set "APKFILE=%USERPROFILE%\Downloads\fusion-capture.apk"
if not defined APKFILE (
  echo [ERROR] fusion-capture.apk not found.
  exit /b 1
)
echo Using: %APKFILE%
"%ADBEXE%" install -r "%APKFILE%"
if errorlevel 1 (
  echo [ERROR] install failed. See message above.
  exit /b 1
)

echo.
echo [3/6] Grant CAMERA
"%ADBEXE%" shell pm grant com.fusion.capture android.permission.CAMERA
if errorlevel 1 echo [WARN] grant failed - allow CAMERA in Settings manually.

echo.
echo [4/6] Push screen-push.jar
set "JARFILE="
if exist "%~dp0screen-push.jar" set "JARFILE=%~dp0screen-push.jar"
if not defined JARFILE if exist "%CD%\screen-push.jar" set "JARFILE=%CD%\screen-push.jar"
if not defined JARFILE if exist "%USERPROFILE%\Downloads\screen-push.jar" set "JARFILE=%USERPROFILE%\Downloads\screen-push.jar"
if defined JARFILE (
  "%ADBEXE%" push "%JARFILE%" /data/local/tmp/screen-push.jar
  "%ADBEXE%" shell chmod 644 /data/local/tmp/screen-push.jar
) else (
  echo [WARN] screen-push.jar not found - screen stream 9114 unavailable.
)

echo.
echo [5/6] Verify
"%ADBEXE%" shell pm list packages ^| findstr fusion
"%ADBEXE%" shell ls -l /data/local/tmp/screen-push.jar

echo.
echo [6/6] Start streams
if "%RECVIP%"=="" (
  echo [INFO] No receiver IP given - streams NOT started.
  echo        Re-run with IP to start, or start manually below.
  goto :debugcmds
)
echo Receiver IP: %RECVIP%
echo Starting screen stream 9114 (shell uid, background)...
"%ADBEXE%" shell "CLASSPATH=/data/local/tmp/screen-push.jar app_process / poc.ScreenPush %RECVIP% 9114 0 640 400 20 2000000 2 1 >/dev/null 2>&1 &"
echo Starting camera stream 9115 (foreground service)...
"%ADBEXE%" shell am start-foreground-service -n com.fusion.capture/.CameraStreamService --es host %RECVIP% --ei port 9115 --ei sec 0 --ei width 640 --ei height 480 --ei fps 30 --ei bitrate 2000000 --ei iframe 2 --ei pts 1
echo Streams started. Check receiver side for frames.

:debugcmds
echo.
echo ============================================================
echo  Debug / verify commands
echo ============================================================
echo Watch logs:
echo   adb logcat -s ScreenPush FusionCamStream
echo Check processes:
echo   adb shell ps -A ^| findstr app_process
echo Verify streams on a PC with Python:
echo   python tools\stream_probe.py listen --seconds 15 --port 9114
echo   python tools\stream_probe.py listen --seconds 15 --port 9115
echo Stop:
echo   adb shell am broadcast -a com.fusion.capture.STOP_CAMERA -n com.fusion.capture/.CaptureReceiver
echo   adb shell am force-stop com.fusion.capture
echo.
echo NOTE: receiver IP must be reachable FROM the glasses.
echo   - iPhone hotspot: iPhone LAN IP (172.20.10.x)
echo   - PC probe: PC LAN IP on the same network
echo Identity check: if [1/6] did NOT show uid=2000, screen stream 9114
echo   will fail - only camera stream 9115 can be verified on this device.
endlocal
