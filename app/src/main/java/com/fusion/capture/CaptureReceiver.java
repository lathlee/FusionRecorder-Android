package com.fusion.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * 采集控制入口。iPhone 端通过无线 ADB 发显式广播（带 -n 组件名，绕过隐式广播限制）：
 *
 * <pre>
 * # 启动相机流
 * adb shell am broadcast -a com.fusion.capture.START_CAMERA \
 *   -n com.fusion.capture/.CaptureReceiver \
 *   --es host 172.20.10.1 --ei port 9115 --ei sec 0 --ei pts 1
 *
 * # 停止相机流
 * adb shell am broadcast -a com.fusion.capture.STOP_CAMERA \
 *   -n com.fusion.capture/.CaptureReceiver
 *
 * # 拉起透明保活
 * adb shell am broadcast -a com.fusion.capture.KEEPALIVE \
 *   -n com.fusion.capture/.CaptureReceiver
 * </pre>
 *
 * 屏幕流（9114）不在这里：它必须以 shell(uid=2000) 的 app_process 身份运行，
 * App 进程没有这个身份。由 iPhone 端直接发 adb shell 命令启动，见 DEPLOY.md。
 */
public final class CaptureReceiver extends BroadcastReceiver {

    private static final String TAG = "FusionCaptureCtl";

    public static final String ACTION_START_CAMERA = "com.fusion.capture.START_CAMERA";
    public static final String ACTION_STOP_CAMERA = "com.fusion.capture.STOP_CAMERA";
    public static final String ACTION_KEEPALIVE = "com.fusion.capture.KEEPALIVE";

    private static void log(String s) {
        Log.i(TAG, s);
        System.out.println("[FUSION-CTL] " + s);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case ACTION_START_CAMERA: {
                Intent svc = new Intent(context, CameraStreamService.class);
                Bundle extras = intent.getExtras();
                if (extras != null) svc.putExtras(extras);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                log("START_CAMERA -> " + (extras == null ? "(no extras)" : extras.toString()));
                break;
            }
            case ACTION_STOP_CAMERA:
                // 0.4.0：不能直接 stopService —— 那会让 onDestroy 立刻打断 muxer，
                // mp4 缺 moov。改成带 ACTION_STOP 再 start 一次，让服务自己
                // 置停止标志、drain 完残留帧、写完 mp4 再 stopSelf()。
                {
                    Intent stop = new Intent(context, CameraStreamService.class);
                    stop.setAction(CameraStreamService.ACTION_STOP);
                    context.startService(stop);
                    log("STOP_CAMERA (graceful)");
                }
                break;
            case ACTION_KEEPALIVE: {
                Intent a = new Intent(context, KeepAliveActivity.class);
                a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Bundle extras = intent.getExtras();
                if (extras != null) a.putExtras(extras);
                context.startActivity(a);
                log("KEEPALIVE (transparent)");
                break;
            }
            default:
                log("unknown action " + action);
                break;
        }
    }
}
