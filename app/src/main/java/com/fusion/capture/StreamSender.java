package com.fusion.capture;

import android.os.SystemClock;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 两路流共用的「MediaCodec Annex-B → 裸 TCP」出口，也是 pts 插入的唯一真源。
 *
 * APK 侧（相机流）与 screen-push.jar 侧（屏幕流）都只调这里，保证两路线上
 * 的 SEI pts 格式、时间基准、心跳规则完全一致，不会出现「预览一套、成片一套」。
 *
 * 协议：每个编码帧输出前先写一条 PtsSei SEI NAL，再写 MediaCodec 原始 Annex-B 数据；
 * SPS/PPS（BUFFER_FLAG_CODEC_CONFIG）直接写、不加 SEI（接收端需要它建解码会话）。
 *
 * 屏幕流静止时 MediaCodec 可能长时间不产帧（交接包坑 #9 VFR）。这里除了给编码器设置
 * repeat-previous-frame-after 之外，再加一层心跳：超过 1 秒没有真实帧就发一条带时间戳的
 * SEI（seq 带心跳标记位，后面不跟视频帧）。旧接收端按 type 6 忽略，新接收端可借此
 * 保持连接活跃、对齐两路时钟、并及时发现对端断开。
 *
 * 2026-11-20 断线自愈（v0.3.9）：
 *   旧版「首连带 45s 重试、中途断了 broken=true 让上层服务退出」在真机暴露致命问题——
 *   WiFi 抖动一次，相机流就永久停推 30+ 秒（成片摄像头画面冻结）。改为：
 *     · 任何一次写失败只置 needReconnect，不判死刑；
 *     · 下一次写（或心跳）前自动重连，带同样的 45s 重试；
 *     · 重连成功后自动补发缓存的 SPS/PPS，让 iPhone 端解码会话能立即重建；
 *     · isBroken() 语义改为「主动关闭」，上层采集循环不再因瞬时断线而自杀。
 *   这样相机前台服务和屏幕进程都能扛住中途断线，无需 iPhone 干预。
 */
public final class StreamSender implements AutoCloseable {

    private final Object lock = new Object();
    private final String host;
    private final int port;
    private final int streamId;
    private final boolean ptsEnabled;
    private final int connectTimeoutMs;

    private Socket socket;
    private OutputStream out;
    private final Thread heartbeatThread;

    /** 累积的 SPS/PPS（Annex-B），断线重连后原样重发，保证新连接能建解码会话。 */
    private byte[] codecConfig = new byte[0];
    /** 写失败后置位：下一次写之前先重连。 */
    private volatile boolean needReconnect = false;
    private volatile boolean closed = false;
    private volatile long lastFrameAtUs;
    private volatile int reconnects = 0;
    private long totalBytes = 0;
    private int frames = 0;
    private int heartbeats = 0;

    /** 连接重试总时长上限（毫秒）。采集端冷启动比 iPhone 开监听快，首连常被拒。 */
    private static final long CONNECT_RETRY_TOTAL_MS = 45_000;
    private static final long CONNECT_RETRY_INTERVAL_MS = 1_000;

    public StreamSender(String host, int port, int streamId, boolean ptsEnabled,
                         int connectTimeoutMs) throws IOException {
        this.host = host;
        this.port = port;
        this.streamId = streamId;
        this.ptsEnabled = ptsEnabled;
        this.connectTimeoutMs = connectTimeoutMs;

        Socket s = connectWithRetry();
        if (s == null) {
            throw new IOException("connect to " + host + ":" + port + " failed (initial, "
                    + CONNECT_RETRY_TOTAL_MS + "ms)");
        }
        configureSocket(s);
        this.socket = s;
        this.out = s.getOutputStream();
        this.lastFrameAtUs = nowUs();
        this.heartbeatThread = new Thread(this::heartbeatLoop, "fusion-pts-heartbeat");
        this.heartbeatThread.setDaemon(true);
        this.heartbeatThread.start();
    }

    private String tag() {
        return "[FUSION-" + (streamId == PtsSei.STREAM_SCREEN ? "SCREEN" : "CAM") + "]";
    }

    /** 带重试的连接：返回 socket，或超时/被关闭时返回 null（不抛）。 */
    private Socket connectWithRetry() {
        Socket s = null;
        long deadline = SystemClock.elapsedRealtime() + CONNECT_RETRY_TOTAL_MS;
        int attempt = 0;
        while (s == null && SystemClock.elapsedRealtime() < deadline && !closed) {
            attempt++;
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(host, port), connectTimeoutMs);
                s = candidate;
                System.out.println(tag() + " connect ok on attempt " + attempt
                        + " -> " + host + ":" + port);
            } catch (IOException e) {
                try { candidate.close(); } catch (Throwable ignored) {}
                System.out.println(tag() + " connect attempt " + attempt
                        + " failed: " + e.getMessage());
                try { Thread.sleep(CONNECT_RETRY_INTERVAL_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return s;
    }

    private static void configureSocket(Socket s) {
        try { s.setTcpNoDelay(true); } catch (Throwable ignored) {}
        try { s.setSendBufferSize(256 * 1024); } catch (Throwable ignored) {}
    }

    /**
     * 写之前确认连接可用；断线则重连并补发 SPS/PPS。必须在 lock 内调用。
     * @return true 可写；false 表示主动关闭或本次重连未成功（调用方丢弃本次数据，下次再试）
     */
    private boolean ensureWritableLocked() {
        if (closed) return false;
        if (!needReconnect && socket != null && !socket.isClosed()) return true;

        System.out.println(tag() + " link lost, reconnecting (#" + (reconnects + 1) + ")...");
        Socket s = connectWithRetry();
        if (s == null) {
            System.out.println(tag() + " reconnect FAILED (will retry on next frame/heartbeat)");
            return false;
        }
        configureSocket(s);
        Socket old = socket;
        try { if (old != null) old.close(); } catch (Throwable ignored) {}
        socket = s;
        try {
            out = s.getOutputStream();
        } catch (IOException e) {
            needReconnect = true;
            return false;
        }
        needReconnect = false;
        reconnects++;
        lastFrameAtUs = nowUs();
        // 新 TCP 连接上的解码器需要参数集才能建会话——把累积的 SPS/PPS 原样重发一次
        if (codecConfig.length > 0) {
            try {
                out.write(codecConfig);
                totalBytes += codecConfig.length;
                System.out.println(tag() + " resent SPS/PPS (" + codecConfig.length + "B)");
            } catch (IOException e) {
                needReconnect = true;
                return false;
            }
        }
        System.out.println(tag() + " reconnected -> " + host + ":" + port);
        return true;
    }

    private void markNeedReconnect(String where, IOException e) {
        needReconnect = true;
        System.out.println(tag() + " " + where + " failed: " + e.getMessage()
                + " -> will reconnect");
    }

    /** 两路进程共享的单调时钟，单位微秒。 */
    public static long nowUs() {
        return SystemClock.elapsedRealtimeNanos() / 1000L;
    }

    /** SPS/PPS 等 codec config 数据，原样写出，不带 SEI；缓存供重连补发。 */
    public void writeConfig(byte[] annexb) throws IOException {
        synchronized (lock) {
            if (closed) throw new IOException("sender closed");
            byte[] merged = new byte[codecConfig.length + annexb.length];
            System.arraycopy(codecConfig, 0, merged, 0, codecConfig.length);
            System.arraycopy(annexb, 0, merged, codecConfig.length, annexb.length);
            codecConfig = merged;
            if (!ensureWritableLocked()) return;
            try {
                out.write(annexb);
                totalBytes += annexb.length;
            } catch (IOException e) {
                markNeedReconnect("writeConfig", e);
            }
        }
    }

    /**
     * 写一个编码帧。pts 取调用时刻的单调时钟；seq 从 0 递增由调用方传入。
     * 写失败不再抛出（避免上层服务自杀），只标记断线，下一帧/心跳自动重连。
     */
    public void writeFrame(byte[] annexb, int seq) throws IOException {
        synchronized (lock) {
            if (closed) throw new IOException("sender closed");
            if (!ensureWritableLocked()) return;
            try {
                if (ptsEnabled) {
                    byte[] sei = PtsSei.build(nowUs(), streamId, seq);
                    out.write(sei);
                    totalBytes += sei.length;
                }
                out.write(annexb);
                totalBytes += annexb.length;
                frames++;
                lastFrameAtUs = nowUs();
            } catch (IOException e) {
                markNeedReconnect("writeFrame", e);
            }
        }
    }

    private void heartbeatOnce() throws IOException {
        byte[] sei = PtsSei.build(nowUs(), streamId,
                PtsSei.SEQ_HEARTBEAT_FLAG | heartbeats);
        out.write(sei);
        totalBytes += sei.length;
        heartbeats++;
        out.flush();
    }

    private void heartbeatLoop() {
        while (!closed) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            if (closed || !ptsEnabled) continue;
            long now = nowUs();
            if (now - lastFrameAtUs < PtsSei.HEARTBEAT_IDLE_US) continue;
            synchronized (lock) {
                if (closed || !ensureWritableLocked()) continue;
                try {
                    heartbeatOnce();
                } catch (IOException e) {
                    markNeedReconnect("heartbeat", e);
                }
            }
        }
    }

    public void flush() throws IOException {
        synchronized (lock) {
            if (!closed && !needReconnect) out.flush();
        }
    }

    /**
     * 是否应停止采集。v0.3.9 起只在主动 close() 后为 true——
     * 瞬时断线由内部重连吸收，不再让上层服务/Camera2 session 自杀。
     */
    public boolean isBroken() {
        return closed;
    }

    public int reconnects() {
        return reconnects;
    }

    public long totalBytes() {
        synchronized (lock) {
            return totalBytes;
        }
    }

    public int frames() {
        synchronized (lock) {
            return frames;
        }
    }

    public int heartbeats() {
        synchronized (lock) {
            return heartbeats;
        }
    }

    /**
     * 主动关闭。iPhone 端可用「断开 9114/9115 TCP」来终止采集；
     * Service 侧也会在 onDestroy 里调它，打断可能阻塞的 write。
     */
    @Override
    public void close() {
        closed = true;
        heartbeatThread.interrupt();
        // 先关 socket：心跳线程可能正卡在 out.write 上并持有 lock，
        // 先关 socket 能让那次 write 立刻抛异常释放 lock，避免 close() 自己等死。
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
        synchronized (lock) {
            try {
                if (out != null) out.flush();
            } catch (Throwable ignored) {
            }
        }
    }
}
