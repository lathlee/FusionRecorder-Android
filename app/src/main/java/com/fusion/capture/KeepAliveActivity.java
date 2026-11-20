package com.fusion.capture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

/**
 * 透明保活 Activity（交接包 §7 待办 3）。
 *
 * 问题：goolton 这台的启动器会在 onResume 时强杀后台应用。前台服务虽然不抢焦点，
 * 但进程仍可能被回收。这个 Activity 完全透明、不可触摸、不拿焦点，
 * 让采集 App 以一个「看得见系统前台 Task、但画面上什么都没有」的状态留在前台：
 *
 *   - 屏幕流（shell uid 虚拟屏镜像）抓到的仍是真实 HUD，不会抓到「● 录制中」界面；
 *   - 触摸/按键事件全部穿透到下层应用，不干扰眼镜正常使用；
 *   - 没有 setContentView，不绘制任何像素，alpha 全 0。
 *
 * 启动：
 * <pre>
 * adb shell am start -n com.fusion.capture/.KeepAliveActivity
 * </pre>
 * 录制结束后：{@code adb shell am force-stop com.fusion.capture}（会同时结束前台服务）。
 */
public final class KeepAliveActivity extends Activity {

    public static final String EXTRA_AUTOSTART = "autostart";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setDimAmount(0f);
        // 完全不抢输入：所有触摸与按键穿透到下层 HUD
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        // 不设 content view：窗口本身即全透明，屏幕流合成后与没有它时逐像素一致

        Intent i = getIntent();
        if (i != null && i.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            Intent svc = new Intent(this, CameraStreamService.class);
            Bundle extras = i.getExtras();
            if (extras != null) svc.putExtras(extras);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        }
    }

    @Override
    public void onBackPressed() {
        // 按返回键不销毁保活窗口，只把 Task 挪到后面
        moveTaskToBack(true);
    }
}
