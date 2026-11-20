# 部署与联调手册（安卓眼镜采集端）

> 目标：让眼镜同时往 iPhone 推两路真实 H264（`9114` 屏幕 / `9115` 相机）。
> 全程不需要 root、不需要 MediaProjection 授权、不需要装厂商包。

---

## 0. 前置条件

| 项 | 要求 |
|---|---|
| 眼镜 | goolton ARGlasses，Android 9 / API 28，无线 ADB 默认开（`service.adb.tcp.port=5555`） |
| 网络 | 眼镜连 **iPhone 个人热点**（或两者同一 Wi-Fi）。iPhone 在热点网段通常是 `172.20.10.1` |
| 主机 | 能跑 `adb`（本机已装 `/opt/android-sdk/platform-tools/adb`） |
| iPhone 端 | `FusionRecorder-iOS` v0.1.2，端口填 9114 / 9115，主机填眼镜能连到的 iPhone IP |
| 产物 | `fusion-capture.apk`、`screen-push.jar`（已放在 `/workspace` 根目录） |

下文用两个占位符，请先替换：

```
<GLASSES_IP>  眼镜的无线地址，例如 192.168.101.133
<IPHONE_IP>   iPhone 在热点网段的地址，例如 172.20.10.1
```

**关键点**：两条启动命令里的 `host` 是 `<IPHONE_IP>`（采集端主动连 iPhone 的监听端口），
不是眼镜自己的 IP。iPhone 端要先打开 9114 / 9115 的监听，再启动采集端。

---

## 1. 构建（本机已完成，可跳过）

```bash
cd /workspace/FusionRecorder-Android
python3 build.py
# 产出 dist/fusion-capture.apk + dist/screen-push.jar，并复制到 /workspace 根目录
```

---

## 2. 部署

```bash
export ADB=/opt/android-sdk/platform-tools/adb
export APK=/workspace/fusion-capture.apk
export JAR=/workspace/screen-push.jar

# 2.1 连无线 ADB，并确认身份是 shell(uid=2000)——抓屏靠这个身份
$ADB connect <GLASSES_IP>:5555
$ADB shell id
# 期望：uid=2000(shell) gid=2000(shell) ...

# 2.2 推屏幕流 dex（纯 dex，不安装）
$ADB push "$JAR" /data/local/tmp/screen-push.jar
$ADB shell chmod 644 /data/local/tmp/screen-push.jar

# 2.3 安装相机流 APK（纯 headless，桌面不会出现图标）
$ADB install -r "$APK"
$ADB shell pm list packages | grep fusion          # 期望 com.fusion.capture

# 2.4 授予相机权限（不授权会打印 FATAL: CAMERA 权限未授予）
$ADB shell pm grant com.fusion.capture android.permission.CAMERA
$ADB shell dumpsys package com.fusion.capture | grep -A2 CAMERA
```

> 没有 LAUNCHER Activity 是**故意的**：录屏期间不该在眼镜上打开任何 App 界面，
> 否则屏幕流抓到的就是采集 App 自己的窗口，而不是真实 HUD。

---

## 3. 启动采集

### 3.1 iPhone 端先开监听

在 iPhone 端 `FusionRecorder` 里点「从眼镜抓两帧」，或直接开始录制流程，
让 `H264StreamReceiver` 监听 9114 / 9115。也可用本工程的探针代替 iPhone 端先验证：

```bash
cd /workspace/FusionRecorder-Android
python3 tools/stream_probe.py listen --seconds 10 --save-prefix /tmp/dual
```

### 3.2 屏幕流（9114，shell uid）

```bash
$ADB shell "CLASSPATH=/data/local/tmp/screen-push.jar \
  app_process / poc.ScreenPush <IPHONE_IP> 9114 0"
```

完整参数（全部可选，按顺序）：

```
poc.ScreenPush <host> <port> <sec> [w h fps bitrate iframe pts repeat_us]
  host        iPhone IP（必填）
  port       默认 9114
  sec        0 = 不限时；>0 = 跑满 N 秒自动结束
  w h        默认 640 400（与眼镜物理屏一致，改了会缩放镜像）
  fps        默认 20
  bitrate    默认 2000000
  iframe     I 帧间隔秒数，默认 2（越小越容易抓到 IDR）
  pts        1=插 SEI pts（默认），0=纯裸流（兼容旧接收端）
  repeat_us  静止重复帧间隔微秒，默认 1000000/fps
```

这条命令会**一直占用 shell 会话**直到采集结束，所以 iPhone 端要用非阻塞方式发
（ADB `shell:` 通道保持打开），或另开一个 adb 会话发第二条命令。

### 3.3 相机流（9115，App uid）

```bash
$ADB shell am start-foreground-service -n com.fusion.capture/.CameraStreamService \
  --es host <IPHONE_IP> --ei port 9115 --ei sec 0 \
  --ei width 640 --ei height 480 --ei fps 30 --ei bitrate 2000000 \
  --ei iframe 2 --ei pts 1
```

等价的广播方式（便于 iPhone 端统一发 `am broadcast`）：

```bash
$ADB shell am broadcast -a com.fusion.capture.START_CAMERA \
  -n com.fusion.capture/.CaptureReceiver \
  --es host <IPHONE_IP> --ei port 9115 --ei sec 0 --ei pts 1
```

### 3.4 可选：透明保活（交接包 §7 待办 3）

如果这台 ROM 的启动器会把后台应用强杀，拉起一个**全透明、不拿焦点、触摸穿透**的 Activity：

```bash
$ADB shell am start -n com.fusion.capture/.KeepAliveActivity
# 或
$ADB shell am broadcast -a com.fusion.capture.KEEPALIVE \
  -n com.fusion.capture/.CaptureReceiver
```

它不绘制任何像素，屏幕流抓到的仍是真实 HUD；触摸/按键事件全部穿透到下层应用。
前台服务本来就不抢焦点，多数情况下这一步可以省略。

---

## 4. iPhone 端 ADBClient 要发的命令清单

给 iOS 端 v0.2 的 `ADBClient` 接入用（现在仍是骨架，RSA 授权待联调）。
每条都是 `shell:` 通道里的完整命令串：

| 步骤 | 命令 |
|---|---|
| 检查身份 | `id` |
| 推 dex | `adb push`（走 `sync:` 或 `shell:` + `printf`，见 ADB 协议） |
| 起屏幕流 | `CLASSPATH=/data/local/tmp/screen-push.jar app_process / poc.ScreenPush <IPHONE_IP> 9114 0` |
| 起相机流 | `am start-foreground-service -n com.fusion.capture/.CameraStreamService --es host <IPHONE_IP> --ei port 9115 --ei sec 0 --ei pts 1` |
| 拉保活 | `am start -n com.fusion.capture/.KeepAliveActivity` |
| 停相机流 | `am broadcast -a com.fusion.capture.STOP_CAMERA -n com.fusion.capture/.CaptureReceiver` |
| 停屏幕流 | 断开 9114 TCP（心跳每秒写一次，采集端最多 1 秒内感知并退出） |
| 全停 | `am force-stop com.fusion.capture` + 断开两条 TCP |

> 授权阶段只需做一次：`pm grant com.fusion.capture android.permission.CAMERA`。

---

## 5. 停止

```bash
# 相机流
$ADB shell am broadcast -a com.fusion.capture.STOP_CAMERA \
  -n com.fusion.capture/.CaptureReceiver
# 或
$ADB shell am force-stop com.fusion.capture

# 屏幕流：iPhone 端断开 9114 的 TCP 连接即可
# 兜底（若 toybox 支持 pkill）
$ADB shell pkill -f poc.ScreenPush
```

`sec=0` 表示不受 `screenrecord` 180 秒上限约束，可以长录。

---

## 6. 验证

```bash
# 6.1 看采集端日志
$ADB logcat -s FusionScreenPush FusionCamStream FusionCaptureCtl

# 期望能看到：
#   [FUSION-SCREEN] start uid=2000 sdk=28
#   [FUSION-SCREEN] mirror display attached to encoder surface
#   [FUSION-SCREEN] t=2.0s frames=40 fps=20.0 bytes=... heartbeat=0
#   [FUSION-CAM] camera opened → repeating preview → encoder
#   [FUSION-CAM] t=2.0s frames=60 fps=30.0 bytes=...

# 6.2 用探针接收两路并统计 pts（替代 iPhone 端先验证）
python3 tools/stream_probe.py listen --seconds 10 --save-prefix /tmp/dual
# 期望输出：两路都有 SPS/PPS、帧数与 fps 合理、ptsSpan ≈ 录制时长、
#           两路配对延迟为稳定的小值（同源单调时钟）

# 6.3 解码单帧核对画面真伪
ffmpeg -i /tmp/dual_screen.h264 -frames:v 1 /tmp/screen.png
ffmpeg -i /tmp/dual_camera.h264 -frames:v 1 /tmp/camera.png

# 6.4 不接眼镜也能验证协议
python3 tools/stream_probe.py selftest
```

---

## 7. 参数对照表

| 含义 | 相机流 intent extra | 屏幕流 argv 位置 | 默认值 |
|---|---|---|---|
| 目标主机 | `--es host` | `[0]` | 必填 |
| 端口 | `--ei port` | `[1]` | 9115 / 9114 |
| 时长（0=不限） | `--ei sec` | `[2]` | 0 / 0 |
| 宽 | `--ei width` | `[3]` | 640 / 640 |
| 高 | `--ei height` | `[4]` | 480 / 400 |
| 帧率 | `--ei fps` | `[5]` | 30 / 20 |
| 码率 bps | `--ei bitrate` | `[6]` | 2000000 |
| I 帧间隔 s | `--ei iframe` | `[7]` | 2 |
| 相机 id | `--es camera` | — | `ids[0]` |
| SEI pts 开关 | `--ei pts` | `[8]` | 1 |
| 静止重复帧 μs | `--ei repeat_us` | `[9]` | 1000000/fps |

改参数前先 STOP，服务在运行时会忽略新的 START（`already running, ignore`）。

---

## 8. Windows 主机（cmd / PowerShell）

本文档其他章节的示例用 bash 反斜杠 `\` 续行，**Windows 的 cmd 不认**（cmd 续行符是 `^`），
`bash` 也不一定装了。请一律写成一行，或直接用 `install_experiment.bat`（无 bash 依赖）。

把 `fusion-capture.apk` 放到 `adb.exe` 同目录，三条命令搞定安装：

```bat
adb install -r fusion-capture.apk
adb shell pm grant com.fusion.capture android.permission.CAMERA
adb shell pm list packages | findstr fusion
```

启动相机流（**必须一整行**，不要换行、不要反斜杠）：

```bat
adb shell am start-foreground-service -n com.fusion.capture/.CameraStreamService --es host <IPHONE_IP> --ei port 9115 --ei sec 0 --ei pts 1
```

屏幕流（一整行，用双引号把整条 shell 命令包起来；cmd 不会像 Git Bash 那样把 `/` 转成路径）：

```bat
adb shell "CLASSPATH=/data/local/tmp/screen-push.jar app_process / poc.ScreenPush <IPHONE_IP> 9114 0"
```

一键脚本：`install_experiment.bat`，会自动在「脚本目录 / 当前目录 / Downloads」里找 APK 与 jar，
并优先用同目录的 `adb.exe`。带参数可顺带启动相机流：

```bat
install_experiment.bat 192.168.1.20
```

> 若 cmd 里出现「'bash' 不是内部或外部命令」，说明在用 Windows 原生 cmd，用上面的 `.bat`
> 或纯 `adb` 命令即可，不要跑 `.sh`。

---

## 9. 排错

| 现象 | 原因 / 处理 |
|---|---|
| `FATAL: CAMERA 权限未授予` | 补 `pm grant com.fusion.capture android.permission.CAMERA` |
| `EACCES (Permission denied)` | 少了 `INTERNET` 权限 —— 本 APK 已声明，别用旧包 |
| `SurfaceControl.createDisplay returned null` | 不是以 shell uid 跑；确认 `adb shell id` 是 `uid=2000`；检查 hidden API 豁免是否失败 |
| `hiddenapi exemptions failed` | ROM 版本差异；看 logcat 全文，多数情况仍可用 |
| 连不上 9114/9115 | iPhone 端监听没开 / 热点隔离 / IP 填错。先在眼镜上 `toybox nc <IPHONE_IP> 9114` 测通路 |
| 抓到黑屏或「● 录制中」界面 | 眼镜上打开了采集 App 的界面。本 APK 无 LAUNCHER；必要时用透明保活 Activity，录屏期间别打开任何 UI |
| 屏幕流长时间没有帧 | 静止 VFR。已加 `repeat-previous-frame-after` + 1 秒心跳；确认 `pts=1` 时心跳才有 |
| 相机画面偏暗 / 几乎纯色 | 交接包已确认是镜头被遮住，不是抓不到；把镜头对准场景重录 |
| 两路帧率差异大 | 正常（屏幕 20fps / 相机 30fps）。按 SEI pts 对齐时间轴补帧，不要按帧号硬对齐 |
| `app_process` 不退出 | 已用 `System.exit(0)`；确认没有另一个 shell 会话还占着 |
| iPhone 端解不出帧 | 先确认收到了 `00 00 00 01 67`（SPS）；I 帧间隔调成 1 秒更容易抓到 IDR |
