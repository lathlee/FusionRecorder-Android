package com.fusion.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 相机流采集（第二路，TCP 9115）。
 *
 * 必须以「装了 APK 的 App 进程」身份运行：shell(uid=2000) 开不了相机
 * （Camera1 会 native abort，Camera2 抛 SecurityException），只有持有
 * android.permission.CAMERA 的应用 uid 才能打开摄像头。见交接包 §2.3。
 *
 * 链路：Camera2 → MediaCodec.createInputSurface() → H264 Annex-B → 裸 TCP。
 * 前台服务、无 UI、不抢焦点，因此 shell uid 的虚拟屏镜像抓到的是真实 HUD，
 * 而不是「● 录制中」这种采集 App 自己的界面。
 *
 * 启动（iPhone 端通过无线 ADB 发送）：
 * <pre>
 * adb shell am start-foreground-service -n com.fusion.capture/.CameraStreamService \
 *   --es host 172.20.10.1 --ei port 9115 --ei sec 0 \
 *   --ei width 640 --ei height 480 --ei fps 30 --ei bitrate 2000000 --ei pts 1
 * </pre>
 *
 * 停止：见 {@link CaptureReceiver} 的 STOP_CAMERA 广播，或直接断开 9115 TCP。
 */
public final class CameraStreamService extends Service {

    private static final String TAG = "FusionCamStream";
    private static final String CHANNEL_ID = "fusion_capture_cam";
    private static final int NOTIFICATION_ID = 2033;

    // ---- intent extra 名（DEPLOY.md / PROTOCOL.md 中的命令按这些名字传参）----
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_SEC = "sec";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BITRATE = "bitrate";
    public static final String EXTRA_IFRAME = "iframe";
    public static final String EXTRA_CAMERA_ID = "camera";
    public static final String EXTRA_PTS = "pts";
    public static final String EXTRA_REPEAT_US = "repeat_us";

    public static final String ACTION_STOP = "com.fusion.capture.STOP_CAMERA";

    private static final int DEFAULT_PORT = 9115;
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int DEFAULT_FPS = 30;
    private static final int DEFAULT_BITRATE = 2_000_000;
    private static final int DEFAULT_IFRAME = 2;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private volatile boolean mRunning = false;
    private volatile boolean mStopping = false;
    private MediaCodec mCodec;
    private CameraDevice mCamera;
    private CameraCaptureSession mSession;
    private StreamSender mSender;
    private HandlerThread mCamThread;
    private Thread mWorker;
    private PowerManager.WakeLock mWakeLock;

    private static void log(String s) {
        Log.i(TAG, s);
        System.out.println("[FUSION-CAM] " + s);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "融合采集 · 相机流", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Camera2 H264 over TCP 9115");
                nm.createNotificationChannel(ch);
            }
        }
    }

    /** 必须在 start-foreground-service 后 5 秒内调用，否则系统会杀掉服务。 */
    private void enterForeground() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_capture)
                .setContentTitle("融合采集 · 相机流")
                .setContentText("Camera2 H264 正在推送到 9115")
                .setOngoing(true);
        startForeground(NOTIFICATION_ID, b.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        enterForeground();
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            log("STOP requested");
            requestStop();
            return START_NOT_STICKY;
        }
        if (mRunning) {
            log("already running, ignore new START (先发 STOP_CAMERA 再改参数)");
            return START_STICKY;
        }

        String host = intent != null ? intent.getStringExtra(EXTRA_HOST) : null;
        if (host == null || host.isEmpty()) {
            log("FATAL: 缺少 --es host <iPhone 热点 IP>");
            requestStop();
            return START_NOT_STICKY;
        }
        int port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT);
        int sec = intent.getIntExtra(EXTRA_SEC, 0);           // 0 = 不限时，直到 STOP / 断开
        int width = intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH);
        int height = intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT);
        int fps = intent.getIntExtra(EXTRA_FPS, DEFAULT_FPS);
        int bitrate = intent.getIntExtra(EXTRA_BITRATE, DEFAULT_BITRATE);
        int iframe = intent.getIntExtra(EXTRA_IFRAME, DEFAULT_IFRAME);
        String cameraId = intent.getStringExtra(EXTRA_CAMERA_ID);
        boolean pts = intent.getIntExtra(EXTRA_PTS, 1) != 0;
        int repeatUs = intent.getIntExtra(EXTRA_REPEAT_US, 1_000_000 / Math.max(1, fps));

        mRunning = true;
        mStopping = false;
        final String h = host;
        mWorker = new Thread(() -> runStream(h, port, sec, width, height, fps,
                bitrate, iframe, cameraId, pts, repeatUs), "fusion-cam-stream");
        mWorker.start();
        return START_STICKY;
    }

    private void runStream(String host, int port, int seconds, int width, int height,
                           int fps, int bitrate, int iframe, String cameraId,
                           boolean pts, int repeatUs) {
        long wallStart = System.currentTimeMillis();
        long deadline = seconds > 0 ? wallStart + seconds * 1000L : Long.MAX_VALUE;
        // 眼镜熄屏/待机时系统可能冻结相机进程、断开 TCP —— 全程持 PARTIAL_WAKE_LOCK
        // 保 CPU 不休眠（屏幕流靠独立进程 + 常亮设置，这路单独兜底）。
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fusion:camera-stream");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
            log("wake lock acquired");
        }
        log("start uid=" + android.os.Process.myUid() + " -> " + host + ":" + port
                + " " + width + "x" + height + " @" + fps + "fps " + bitrate + "bps"
                + " pts=" + pts + " sec=" + (seconds > 0 ? seconds : "unlimited"));
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                log("FATAL: CAMERA 权限未授予。先执行："
                        + "adb shell pm grant com.fusion.capture android.permission.CAMERA");
                return;
            }

            mSender = new StreamSender(host, port, PtsSei.STREAM_CAMERA, pts,
                    CONNECT_TIMEOUT_MS);
            log("socket connected " + host + ":" + port);

            MediaFormat fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframe);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // 静止场景也让编码器持续产帧，避免 VFR 导致接收端抓不到帧
            if (repeatUs > 0) {
                fmt.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatUs);
            }
            mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mCodec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            final Surface encoderSurface = mCodec.createInputSurface();
            mCodec.start();
            log("encoder started " + width + "x" + height + " @" + fps
                    + " repeatUs=" + repeatUs);

            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            log("camera ids=" + Arrays.toString(ids));
            final String useId = (cameraId != null && !cameraId.isEmpty()) ? cameraId : ids[0];
            CameraCharacteristics cc = cm.getCameraCharacteristics(useId);
            log("camera " + useId + " sizes(SurfaceTexture)=" + Arrays.toString(
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            .getOutputSizes(SurfaceTexture.class)));

            mCamThread = new HandlerThread("fusion-cam2");
            mCamThread.start();
            Handler handler = new Handler(mCamThread.getLooper());
            final CountDownLatch opened = new CountDownLatch(1);
            final CountDownLatch configured = new CountDownLatch(1);
            final String[] error = new String[1];

            cm.openCamera(useId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    mCamera = camera;
                    log("camera opened " + useId);
                    try {
                        camera.createCaptureSession(Arrays.asList(encoderSurface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        mSession = session;
                                        try {
                                            CaptureRequest.Builder req = camera
                                                    .createCaptureRequest(
                                                            CameraDevice.TEMPLATE_RECORD);
                                            req.addTarget(encoderSurface);
                                            req.set(CaptureRequest.CONTROL_MODE,
                                                    CaptureRequest.CONTROL_MODE_AUTO);
                                            session.setRepeatingRequest(req.build(), null, handler);
                                            log("repeating preview -> encoder");
                                        } catch (Throwable t) {
                                            error[0] = "request: " + t;
                                        }
                                        configured.countDown();
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        error[0] = "onConfigureFailed";
                                        configured.countDown();
                                    }
                                }, handler);
                    } catch (Throwable t) {
                        error[0] = "createCaptureSession: " + t;
                        configured.countDown();
                    }
                    opened.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    error[0] = "onDisconnected";
                    opened.countDown();
                    configured.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int err) {
                    error[0] = "onError " + err;
                    opened.countDown();
                    configured.countDown();
                }
            }, handler);

            if (!opened.await(6, TimeUnit.SECONDS)) {
                log("FATAL: openCamera timeout");
                return;
            }
            if (!configured.await(6, TimeUnit.SECONDS)) {
                log("FATAL: session timeout " + error[0]);
                return;
            }
            if (error[0] != null) {
                log("FATAL: " + error[0]);
                return;
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastReport = System.currentTimeMillis();
            int framesSinceReport = 0;
            int seq = 0;

            while (!mStopping && !mSender.isBroken()
                    && System.currentTimeMillis() < deadline) {
                int idx = mCodec.dequeueOutputBuffer(info, 200_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("output format " + mCodec.getOutputFormat());
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = mCodec.getOutputBuffer(idx);
                if (buf != null && info.size > 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    buf.get(data);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // SPS/PPS：原样先送出去，接收端靠它建 VTDecompressionSession
                        mSender.writeConfig(data);
                    } else {
                        mSender.writeFrame(data, seq++);
                        framesSinceReport++;
                    }
                }
                mCodec.releaseOutputBuffer(idx, false);

                long now = System.currentTimeMillis();
                if (now - lastReport >= 2000) {
                    double fpsNow = framesSinceReport * 1000.0 / (now - lastReport);
                    log("t=" + (now - wallStart) / 1000.0 + "s frames=" + mSender.frames()
                            + " fps=" + String.format("%.1f", fpsNow)
                            + " bytes=" + mSender.totalBytes()
                            + " reconnects=" + mSender.reconnects()
                            + " heartbeat=" + mSender.heartbeats());
                    framesSinceReport = 0;
                    lastReport = now;
                }
            }
            mSender.flush();
            log("DONE frames=" + mSender.frames() + " bytes=" + mSender.totalBytes()
                    + " reconnects=" + mSender.reconnects()
                    + " elapsed=" + (System.currentTimeMillis() - wallStart) + "ms");
        } catch (Throwable t) {
            log("FATAL " + t);
            t.printStackTrace(System.out);
        } finally {
            cleanup();
            if (mWakeLock != null) {
                try { mWakeLock.release(); } catch (Throwable ignored) {}
                log("wake lock released");
                mWakeLock = null;
            }
            mRunning = false;
            stopForeground(true);
            stopSelf();
        }
    }

    private void cleanup() {
        if (mSession != null) {
            try {
                mSession.stopRepeating();
                mSession.close();
            } catch (Throwable ignored) {
            }
            mSession = null;
        }
        if (mCamera != null) {
            try {
                mCamera.close();
            } catch (Throwable ignored) {
            }
            mCamera = null;
        }
        if (mCodec != null) {
            try {
                mCodec.stop();
                mCodec.release();
            } catch (Throwable ignored) {
            }
            mCodec = null;
        }
        if (mSender != null) {
            mSender.close();   // 顺便打断可能阻塞的 socket write
            mSender = null;
        }
        if (mCamThread != null) {
            mCamThread.quitSafely();
            mCamThread = null;
        }
        log("cleaned up");
    }

    private void requestStop() {
        mStopping = true;
        StreamSender s = mSender;
        if (s != null) s.close();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        mStopping = true;
        StreamSender s = mSender;
        if (s != null) s.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
