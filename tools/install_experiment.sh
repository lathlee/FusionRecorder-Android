#!/usr/bin/env bash
# 融合采集 · 平板实验一键安装
#
# 在「平板已经用 adb 连上的那台电脑」上运行，把 fusion-capture.apk 和本脚本放在同一目录：
#     bash install_experiment.sh
#     HOST_IP=192.168.1.20 bash install_experiment.sh --start   # 顺带把相机流也启动
#
# 会做：检查设备 → 装 APK → 授权 CAMERA → 推 screen-push.jar → 打印下一步命令。
# 找不到 adb 时用 ADB=/path/to/adb 指定。

set -euo pipefail

ADB="${ADB:-adb}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
START=0
[ "${1:-}" = "--start" ] && START=1

find_file() {
    local name="$1" p
    for p in "$DIR/$name" "$PWD/$name" "$HOME/Downloads/$name" "$HOME/桌面/$name"; do
        if [ -f "$p" ]; then echo "$p"; return 0; fi
    done
    echo ""
}

echo "========== 1/5 检查设备 =========="
"$ADB" devices -l
if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "!! 没检测到 adb 设备。"
    echo "   USB 连接：确认平板已开启 USB 调试并授权了这台电脑"
    echo "   无线连接：adb connect <平板IP>:5555  （Android 11+ 用「无线调试」里的 IP:端口）"
    exit 1
fi
echo "型号    : $("$ADB" shell getprop ro.product.model | tr -d '\r')"
echo "Android : $("$ADB" shell getprop ro.build.version.release | tr -d '\r') / SDK $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "shell   : $("$ADB" shell id | tr -d '\r')"

APK="$(find_file fusion-capture.apk)"
JAR="$(find_file screen-push.jar)"
if [ -z "$APK" ]; then
    echo "!! 找不到 fusion-capture.apk，请把它和本脚本放在同一目录"
    exit 1
fi

echo "========== 2/5 安装 APK =========="
"$ADB" install -r "$APK"

echo "========== 3/5 授权相机权限 =========="
if ! "$ADB" shell pm grant com.fusion.capture android.permission.CAMERA; then
    echo "   pm grant 失败：部分 ROM 需要在系统设置里手动给「融合采集」相机权限"
fi
"$ADB" shell dumpsys package com.fusion.capture | grep -m1 -A2 "android.permission.CAMERA" || true

echo "========== 4/5 推送屏幕流 dex =========="
if [ -n "$JAR" ]; then
    "$ADB" push "$JAR" /data/local/tmp/screen-push.jar
    "$ADB" shell chmod 644 /data/local/tmp/screen-push.jar
else
    echo "   未找到 screen-push.jar，跳过（只验证相机流不需要它）"
fi

echo "========== 5/5 完成 =========="
echo "已安装 com.fusion.capture（纯后台采集服务，桌面不会出现图标，这是正常的）"
echo
echo "接下来验证相机流 9115："
echo "  ① 这台电脑上先开接收端（另开一个终端）："
echo "     python3 tools/stream_probe.py listen --seconds 10 --save-prefix /tmp/dual"
echo "  ② 启动采集（把 <本机IP> 换成这台电脑在平板同网段的地址）："
echo "     $ADB shell am start-foreground-service -n com.fusion.capture/.CameraStreamService \\"
echo "       --es host <本机IP> --ei port 9115 --ei sec 0 --ei pts 1"
echo "  ③ 看日志：$ADB logcat -s FusionCamStream"
echo
echo "注意：平板 ≠ 眼镜。9114 屏幕流依赖 shell(uid=2000) + SurfaceControl 虚拟屏镜像，"
echo "      这是给 Android 9 眼镜定制的，新版 Android 平板上大概率跑不通。"
echo "      做实验先只验证 9115 相机流，屏幕流等接上真眼镜再测。"

if [ "$START" = "1" ]; then
    if [ -z "${HOST_IP:-}" ]; then
        echo "!! --start 需要 HOST_IP，例如 HOST_IP=192.168.1.20 bash $0 --start"
        exit 1
    fi
    echo
    echo "========== 按 --start 启动相机流 → $HOST_IP:9115 =========="
    "$ADB" shell am start-foreground-service -n com.fusion.capture/.CameraStreamService \
        --es host "$HOST_IP" --ei port 9115 --ei sec 0 --ei pts 1
    echo "已启动。10 秒后停：$ADB shell am broadcast -a com.fusion.capture.STOP_CAMERA -n com.fusion.capture/.CaptureReceiver"
fi
