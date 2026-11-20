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
 */
public final class StreamSender implements AutoCloseable {

    private final Object lock = new Object();
    private final Socket socket;
    private final OutputStream out;
    private final int streamId;
    private final boolean ptsEnabled;
    private final Thread heartbeatThread;

    private volatile boolean closed = false;
    private volatile boolean broken = false;
    private volatile long lastFrameAtUs;
    private long totalBytes = 0;
    private int frames = 0;
    private int heartbeats = 0;

    public StreamSender(String host, int port, int streamId, boolean ptsEnabled,
                         int connectTimeoutMs) throws IOException {
        this.streamId = streamId;
        this.ptsEnabled = ptsEnabled;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        s.setTcpNoDelay(true);
        try {
            s.setSendBufferSize(256 * 1024);
        } catch (Throwable ignored) {
            // 个别 ROM 不允许改发送缓冲，忽略即可
        }
        this.socket = s;
        this.out = s.getOutputStream();
        this.lastFrameAtUs = nowUs();
        this.heartbeatThread = new Thread(this::heartbeatLoop, "fusion-pts-heartbeat");
        this.heartbeatThread.setDaemon(true);
        this.heartbeatThread.start();
    }

    /** 两路进程共享的单调时钟，单位微秒。 */
    public static long nowUs() {
        return SystemClock.elapsedRealtimeNanos() / 1000L;
    }

    /** SPS/PPS 等 codec config 数据，原样写出，不带 SEI。 */
    public void writeConfig(byte[] annexb) throws IOException {
        synchronized (lock) {
            ensureOpen();
            out.write(annexb);
            totalBytes += annexb.length;
        }
    }

    /**
     * 写一个编码帧。pts 取调用时刻的单调时钟；seq 从 0 递增由调用方传入。
     */
    public void writeFrame(byte[] annexb, int seq) throws IOException {
        synchronized (lock) {
            ensureOpen();
            if (ptsEnabled) {
                byte[] sei = PtsSei.build(nowUs(), streamId, seq);
                out.write(sei);
                totalBytes += sei.length;
            }
            out.write(annexb);
            totalBytes += annexb.length;
            frames++;
            lastFrameAtUs = nowUs();
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
        while (!closed && !broken) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            if (closed || !ptsEnabled) continue;
            long now = nowUs();
            if (now - lastFrameAtUs < PtsSei.HEARTBEAT_IDLE_US) continue;
            synchronized (lock) {
                if (closed || broken) return;
                try {
                    heartbeatOnce();
                } catch (IOException e) {
                    broken = true;
                    return;
                }
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("sender closed");
        if (broken) throw new IOException("peer disconnected (detected by heartbeat)");
    }

    public void flush() throws IOException {
        synchronized (lock) {
            if (!closed && !broken) out.flush();
        }
    }

    public boolean isBroken() {
        return broken;
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
            socket.close();
        } catch (Throwable ignored) {
        }
        synchronized (lock) {
            try {
                out.flush();
            } catch (Throwable ignored) {
            }
        }
    }
}
