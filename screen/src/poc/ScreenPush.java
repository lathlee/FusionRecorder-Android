package poc;

import android.graphics.Rect;
import android.media.MediaCodecInfo;
import android.os.IBinder;
import android.view.Surface;

import com.fusion.capture.Mp4Recorder;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 屏幕本地录制（第一路）—— 离线录制架构 0.4.0 起替代 TCP 9114 推流。
 *
 * 必须以 shell(uid=2000) 身份运行，靠无线 ADB 启动，不需要 root / MediaProjection /
 * CAPTURE_VIDEO_OUTPUT / 厂商包：
 * <pre>
 * CLASSPATH=/data/local/tmp/screen-push.jar app_process / poc.ScreenPush \
 *   /data/local/tmp/fusion_screen.mp4 0 [w h fps bitrate iframe pts repeat_us]
 * </pre>
 *
 * 原理：VMRuntime 全量豁免 hidden API → 反射 SurfaceControl.createDisplay 建虚拟屏 →
 * setDisplaySurface(MediaCodec input Surface) 把整个 display 镜像直连硬编（scrcpy 同款，
 * 零 CPU 拷贝）→ MediaMuxer 直接写本地 mp4。全程无网络，彻底摆脱 WiFi 抖动冻结。
 *
 * 停止：iPhone 端执行 `touch /data/local/tmp/fusion_stop_screen`。
 * 主循环每轮检查这个标记文件，置位后 break → signalEndOfInputStream → drain EOS →
 * muxer.stop()，保证 mp4 容器完整、尾部不丢帧。启动时会自动删除旧标记。
 *
 * pts 时基是 SystemClock.elapsedRealtimeNanos()/1000，与相机那路同源，
 * iPhone 端可直接按绝对 pts 对齐两路并按短的那路裁剪。
 */
public final class ScreenPush {

    private static final String TAG = "FusionScreenPush";
    private static final String DEFAULT_PATH = "/data/local/tmp/fusion_screen.mp4";
    private static final String STOP_FILE = "/data/local/tmp/fusion_stop_screen";

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 400;
    private static final int DEFAULT_FPS = 20;
    private static final int DEFAULT_BITRATE = 2_000_000;
    private static final int DEFAULT_IFRAME = 2;

    private static void log(String s) {
        System.out.println("[FUSION-SCREEN] " + s);
        android.util.Log.i(TAG, s);
    }

    /** 全量豁免 hidden API，否则 SurfaceControl 反射会被拒。 */
    private static void exemptions() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Method get = vm.getDeclaredMethod("getRuntime");
            get.setAccessible(true);
            Object rt = get.invoke(null);
            Method set = vm.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            set.setAccessible(true);
            set.invoke(rt, (Object) new String[]{"L"});
        } catch (Throwable t) {
            log("hiddenapi exemptions failed: " + t);
        }
    }

    private static Method sc(String name, Class<?>... types) throws Exception {
        Class<?> c = Class.forName("android.view.SurfaceControl");
        Method m = c.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    private static int arg(String[] a, int i, int def) {
        if (i >= a.length) return def;
        try {
            return Integer.parseInt(a[i]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void main(String[] args) {
        exemptions();
        log("start uid=" + android.os.Process.myUid()
                + " sdk=" + android.os.Build.VERSION.SDK_INT + " (offline mp4)");

        if (args.length < 1) {
            log("usage: ScreenPush <outPath> <sec> [w h fps bitrate iframe pts repeat_us]");
            log("  sec=0 表示不限时，直到出现 " + STOP_FILE);
            return;
        }
        String path = args[0];
        int seconds = arg(args, 1, 0);
        int width = arg(args, 2, DEFAULT_WIDTH);
        int height = arg(args, 3, DEFAULT_HEIGHT);
        int fps = arg(args, 4, DEFAULT_FPS);
        int bitrate = arg(args, 5, DEFAULT_BITRATE);
        int iframe = arg(args, 6, DEFAULT_IFRAME);
        int repeatUs = arg(args, 8, 1_000_000 / Math.max(1, fps));

        // 清掉上一次的停止标记，否则新录制一启动就立刻结束
        File stopFlag = new File(STOP_FILE);
        if (stopFlag.exists()) {
            //noinspection ResultOfMethodCallIgnored
            stopFlag.delete();
        }

        Mp4Recorder recorder = null;
        IBinder vd = null;
        long wallStart = System.currentTimeMillis();
        long deadline = seconds > 0 ? wallStart + seconds * 1000L : Long.MAX_VALUE;

        try {
            recorder = new Mp4Recorder(path, width, height, fps, bitrate,
                    iframe, repeatUs, msg -> log(msg));
            recorder.start();
            Surface input = recorder.inputSurface();

            // 建虚拟屏并把它的 surface 指到编码器输入
            Method createDisplay = sc("createDisplay", String.class, boolean.class);
            Method setSurface = sc("setDisplaySurface", IBinder.class, Surface.class);
            Method setProjection = sc("setDisplayProjection", IBinder.class, int.class,
                    Rect.class, Rect.class);
            Method setLayerStack = sc("setDisplayLayerStack", IBinder.class, int.class);
            Method openTx = sc("openTransaction");
            Method closeTx = sc("closeTransaction");

            vd = (IBinder) createDisplay.invoke(null, "fusion_mirror", true);
            if (vd == null) {
                throw new IllegalStateException("SurfaceControl.createDisplay returned null "
                        + "(uid 不是 shell? hidden API 豁免失败?)");
            }
            Rect full = new Rect(0, 0, width, height);
            openTx.invoke(null);
            try {
                setSurface.invoke(null, vd, input);
                setProjection.invoke(null, vd, 0, full, full); // 0 = ROTATION_0
                setLayerStack.invoke(null, vd, 0);
            } finally {
                closeTx.invoke(null);
            }
            log("mirror display attached to local encoder surface");

            long lastReport = wallStart;
            int framesAtReport = 0;
            while (!stopFlag.exists() && !recorder.isEos()
                    && System.currentTimeMillis() < deadline) {
                int n = recorder.drainOnce(200_000L);
                if (n == 1) framesAtReport++;
                if (n == 2) break;

                long now = System.currentTimeMillis();
                if (now - lastReport >= 2000) {
                    double fpsNow = framesAtReport * 1000.0 / (now - lastReport);
                    log("t=" + (now - wallStart) / 1000.0 + "s frames=" + recorder.frames()
                            + " fps=" + String.format("%.1f", fpsNow)
                            + " pts=" + recorder.firstPTSUs() + ".." + recorder.lastPTSUs());
                    framesAtReport = 0;
                    lastReport = now;
                }
            }
            log("stop flag detected, finishing mp4...");
        } catch (Throwable t) {
            log("FATAL " + t);
            t.printStackTrace(System.out);
        } finally {
            if (vd != null) {
                try {
                    sc("destroyDisplay", IBinder.class).invoke(null, vd);
                    log("mirror display destroyed");
                } catch (Throwable t) {
                    log("destroyDisplay failed " + t);
                }
            }
            if (recorder != null) recorder.stopAndRelease();
            if (stopFlag.exists()) {
                //noinspection ResultOfMethodCallIgnored
                stopFlag.delete();
            }
            log("cleaned up");
            System.out.flush();
            System.err.flush();
            // app_process 里编码器可能留下非 daemon 线程，显式退出保证 ADB shell 能返回
            System.exit(0);
        }
    }
}
