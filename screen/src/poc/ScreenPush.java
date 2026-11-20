package poc;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.IBinder;
import android.view.Surface;

import com.fusion.capture.PtsSei;
import com.fusion.capture.StreamSender;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * 屏幕流采集（第一路，TCP 9114）—— 交接包 §2.2 实测跑通方案的正式版。
 *
 * 必须以 shell(uid=2000) 身份运行，靠无线 ADB 启动，不需要 root / MediaProjection /
 * CAPTURE_VIDEO_OUTPUT / 厂商包：
 * <pre>
 * CLASSPATH=/data/local/tmp/screen-push.jar app_process / poc.ScreenPush \
 *   &lt;iPhone热点IP&gt; 9114 0 [width height fps bitrate iframe pts repeat_us]
 * </pre>
 *
 * 原理：VMRuntime 全量豁免 hidden API → 反射 SurfaceControl.createDisplay 建虚拟屏 →
 * setDisplaySurface(MediaCodec input Surface) 把整个 display 镜像直连硬编（scrcpy 同款，
 * 零 CPU 拷贝）→ H264 Annex-B 经 StreamSender 加 SEI pts 后走裸 TCP。
 *
 * 与交接包 PoC 的差异（都是为了能与 iPhone 端长期联调）：
 *   1. 参数化 host/port/sec/分辨率/帧率/码率；
 *   2. 每个编码帧前插一条 SEI pts（向后兼容，旧接收端按 type 6 忽略）；
 *   3. KEY_REPEAT_PREVIOUS_FRAME_AFTER 让静止画面也持续产帧（修 VFR 抓不到帧的坑）；
 *   4. 空闲心跳 SEI 保活 + 检测 iPhone 端断开；
 *   5. sec=0 表示不限时，iPhone 端断开 9114 TCP 即结束，不受 screenrecord 180s 限制。
 */
public final class ScreenPush {

    private static final String TAG = "FusionScreenPush";

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 400;
    private static final int DEFAULT_FPS = 20;
    private static final int DEFAULT_BITRATE = 2_000_000;
    private static final int DEFAULT_IFRAME = 2;
    private static final int CONNECT_TIMEOUT_MS = 5000;

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
                + " sdk=" + android.os.Build.VERSION.SDK_INT);

        if (args.length < 3) {
            log("usage: ScreenPush <host> <port> <sec> [w h fps bitrate iframe pts repeat_us]");
            log("  sec=0 表示不限时，直到接收端断开 TCP");
            return;
        }
        String host = args[0];
        int port = arg(args, 1, 9114);
        int seconds = arg(args, 2, 0);
        int width = arg(args, 3, DEFAULT_WIDTH);
        int height = arg(args, 4, DEFAULT_HEIGHT);
        int fps = arg(args, 5, DEFAULT_FPS);
        int bitrate = arg(args, 6, DEFAULT_BITRATE);
        int iframe = arg(args, 7, DEFAULT_IFRAME);
        boolean pts = arg(args, 8, 1) != 0;
        int repeatUs = arg(args, 9, 1_000_000 / Math.max(1, fps));

        MediaCodec codec = null;
        StreamSender sender = null;
        IBinder vd = null;
        long wallStart = System.currentTimeMillis();
        long deadline = seconds > 0 ? wallStart + seconds * 1000L : Long.MAX_VALUE;

        try {
            sender = new StreamSender(host, port, PtsSei.STREAM_SCREEN, pts,
                    CONNECT_TIMEOUT_MS);
            log("connected to " + host + ":" + port);

            MediaFormat fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframe);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            if (repeatUs > 0) {
                fmt.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatUs);
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface input = codec.createInputSurface();
            codec.start();
            log("MediaCodec started " + width + "x" + height + " @" + fps + "fps "
                    + bitrate + "bps repeatUs=" + repeatUs + " pts=" + pts);

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
            log("mirror display attached to encoder surface");

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastReport = wallStart;
            int framesSinceReport = 0;
            int configs = 0;
            int seq = 0;

            while (!sender.isBroken() && System.currentTimeMillis() < deadline) {
                int idx = codec.dequeueOutputBuffer(info, 200_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("output format: " + codec.getOutputFormat());
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = codec.getOutputBuffer(idx);
                if (buf != null && info.size > 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    buf.get(data);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        sender.writeConfig(data);   // SPS/PPS 原样先发
                        configs++;
                    } else {
                        sender.writeFrame(data, seq++);
                        framesSinceReport++;
                    }
                }
                codec.releaseOutputBuffer(idx, false);

                long now = System.currentTimeMillis();
                if (now - lastReport >= 2000) {
                    double fpsNow = framesSinceReport * 1000.0 / (now - lastReport);
                    log("t=" + (now - wallStart) / 1000.0 + "s frames=" + sender.frames()
                            + " fps=" + String.format("%.1f", fpsNow)
                            + " bytes=" + sender.totalBytes()
                            + " heartbeat=" + sender.heartbeats());
                    framesSinceReport = 0;
                    lastReport = now;
                }
            }
            sender.flush();
            long elapsed = System.currentTimeMillis() - wallStart;
            log("DONE frames=" + sender.frames() + " configs=" + configs
                    + " bytes=" + sender.totalBytes() + " elapsed=" + elapsed + "ms"
                    + " avgFps=" + String.format("%.1f",
                    sender.frames() * 1000.0 / Math.max(1, elapsed)));
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
            if (codec != null) {
                try {
                    codec.stop();
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            if (sender != null) sender.close();
            log("cleaned up");
            System.out.flush();
            System.err.flush();
            // app_process 里编码器可能留下非 daemon 线程，显式退出保证 ADB shell 能返回
            System.exit(0);
        }
    }
}
