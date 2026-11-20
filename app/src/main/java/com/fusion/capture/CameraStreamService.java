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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 相机本地录制服务（第二路）—— 离线录制架构 0.4.0 起替代 TCP 9115 推流。
 *
 * 链路：Camera2 → MediaCodec.createInputSurface() → MediaMuxer →
 *       /data/local/tmp/fusion_camera.mp4
 *
 * 必须以「装了 APK 的 App 进程」身份运行：shell(uid=2000) 开不了相机。
 * 前台服务、无 UI、不抢焦点，因此 shell uid 的虚拟屏镜像抓到的是真实 HUD。
 *
 * 启动（iPhone 端通过无线 ADB 下发）：
 * <pre>
 * am start-foreground-service -n com.fusion.capture/.CameraStreamService \
 *   --es path /data/local/tmp/fusion_camera.mp4 --ei sec 0 \
 *   --ei width 640 --ei height 480 --ei fps 30 --ei bitrate 2000000 --ei iframe 2
 * </pre>
 *
 * 停止（走 onStartCommand 的 ACTION_STOP，让 worker 有机会把 mp4 moov 写完）：
 * <pre>
 * am startservice -n com.fusion.capture/.CameraStreamService \
 *   -a com.fusion.capture.STOP_CAMERA
 * </pre>
 */
public final class CameraStreamService extends Service {

    private static final String TAG = "FusionCamStream";
    private static final String CHANNEL_ID = "fusion_capture_cam";
    private static final int NOTIFICATION_ID = 2033;

    // ---- intent extra 名（兼容旧字段：host/port/pts 保留但不再使用）----
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
    public static final String EXTRA_PATH = "path";

    public static final String ACTION_STOP = "com.fusion.capture.STOP_CAMERA";

    public static final String DEFAULT_PATH = "/data/local/tmp/fusion_camera.mp4";
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int DEFAULT_FPS = 30;
    private static final int DEFAULT_BITRATE = 2_000_000;
    private static final int DEFAULT_IFRAME = 2;

    private volatile boolean mRunning = false;
    private volatile boolean mStopping = false;
    private Mp4Recorder mRecorder;
    private CameraDevice mCamera;
    private CameraCaptureSession mSession;
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
                ch.setDescription("Camera2 H264 → local mp4");
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
                .setContentText("Camera2 H264 正在本地录制 mp4")
                .setOngoing(true);
        startForeground(NOTIFICATION_ID, b.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        enterForeground();
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            log("STOP requested (graceful: 等待 mp4 写完)");
            requestStop();
            return START_NOT_STICKY;
        }
        if (mRunning) {
            log("already recording, ignore new START (先发 STOP_CAMERA 再改参数)");
            return START_STICKY;
        }

        String path = intent != null ? intent.getStringExtra(EXTRA_PATH) : null;
        if (path == null || path.isEmpty()) path = DEFAULT_PATH;
        int sec = intent != null ? intent.getIntExtra(EXTRA_SEC, 0) : 0;
        int width = intent != null ? intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH) : DEFAULT_WIDTH;
        int height = intent != null ? intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT) : DEFAULT_HEIGHT;
        int fps = intent != null ? intent.getIntExtra(EXTRA_FPS, DEFAULT_FPS) : DEFAULT_FPS;
        int bitrate = intent != null ? intent.getIntExtra(EXTRA_BITRATE, DEFAULT_BITRATE) : DEFAULT_BITRATE;
        int iframe = intent != null ? intent.getIntExtra(EXTRA_IFRAME, DEFAULT_IFRAME) : DEFAULT_IFRAME;
        String cameraId = intent != null ? intent.getStringExtra(EXTRA_CAMERA_ID) : null;
        int repeatUs = intent != null ? intent.getIntExtra(EXTRA_REPEAT_US, 1_000_000 / Math.max(1, fps))
                : 1_000_000 / Math.max(1, fps);

        mRunning = true;
        mStopping = false;
        final String p = path;
        mWorker = new Thread(() -> runRecord(p, sec, width, height, fps,
                bitrate, iframe, cameraId, repeatUs), "fusion-cam-record");
        mWorker.start();
        return START_STICKY;
    }

    private void runRecord(String path, int seconds, int width, int height,
                           int fps, int bitrate, int iframe, String cameraId,
                           int repeatUs) {
        long wallStart = System.currentTimeMillis();
        long deadline = seconds > 0 ? wallStart + seconds * 1000L : Long.MAX_VALUE;

        // 本地录制同样要保 CPU 不休眠，避免眼镜待机冻住编码器。
        // 0.3.9 曾因 Manifest 漏声明 WAKE_LOCK 让 acquire() 抛 SecurityException
        // 直接打死录制线程 —— 这里必须包住，权限异常最多只是不持锁，不能让整路录制崩掉。
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fusion:camera-record");
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire();
                log("wake lock acquired");
            }
        } catch (Throwable t) {
            log("wake lock unavailable (继续录制，不持锁): " + t);
            mWakeLock = null;
        }
        log("start uid=" + android.os.Process.myUid() + " -> " + path
                + " " + width + "x" + height + " @" + fps + "fps " + bitrate + "bps"
                + " sec=" + (seconds > 0 ? seconds : "unlimited"));
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                log("FATAL: CAMERA 权限未授予。先执行："
                        + "adb shell pm grant com.fusion.capture android.permission.CAMERA");
                return;
            }

            mRecorder = new Mp4Recorder(path, width, height, fps, bitrate,
                    iframe, repeatUs, msg -> log(msg));
            mRecorder.start();
            final Surface encoderSurface = mRecorder.inputSurface();

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
                                            log("repeating preview -> local encoder");
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

            long lastReport = System.currentTimeMillis();
            int framesAtReport = 0;
            while (!mStopping && !mRecorder.isEos()
                    && System.currentTimeMillis() < deadline) {
                int n = mRecorder.drainOnce(200_000L);
                if (n == 1) framesAtReport++;
                if (n == 2) break;

                long now = System.currentTimeMillis();
                if (now - lastReport >= 2000) {
                    log("t=" + (now - wallStart) / 1000.0 + "s frames="
                            + mRecorder.frames() + " fps=" + String.format("%.1f",
                            framesAtReport * 1000.0 / (now - lastReport))
                            + " pts=" + mRecorder.firstPTSUs() + ".." + mRecorder.lastPTSUs());
                    framesAtReport = 0;
                    lastReport = now;
                }
            }
            log("DONE frames=" + mRecorder.frames()
                    + " elapsed=" + (System.currentTimeMillis() - wallStart) + "ms");
        } catch (Throwable t) {
            log("FATAL " + t);
            t.printStackTrace(System.out);
        } finally {
            cleanup();   // 先停相机输入，再 mRecorder.stopAndRelease() 写 moov
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
        if (mRecorder != null) {
            mRecorder.stopAndRelease();
            mRecorder = null;
        }
        if (mCamThread != null) {
            mCamThread.quitSafely();
            mCamThread = null;
        }
        log("cleaned up");
    }

    /** 只置停止标志；真正收尾在 worker 的 finally，保证 mp4 写完。 */
    private void requestStop() {
        mStopping = true;
    }

    @Override
    public void onDestroy() {
        mStopping = true;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
