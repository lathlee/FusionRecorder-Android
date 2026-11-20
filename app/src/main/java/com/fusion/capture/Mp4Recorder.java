package com.fusion.capture;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 本地 H264 → MP4 录制器（离线录制架构，0.4.0 起替代实时 TCP 推流）。
 *
 * 设计要点：
 *  1. 输入是 Surface（Camera2 的 encoder surface / SurfaceControl 虚拟屏），
 *     输出是单个标准 mp4 文件，编码全程在眼镜本地完成，不依赖网络。
 *  2. 两路共用 SystemClock.elapsedRealtimeNanos() 作为 pts 时基（开机以来的单调时钟），
 *     所以屏幕和相机两个 mp4 的 presentationTimeUs 可以直接比较/对齐；
 *     iPhone 端取 max(两路首帧) → min(两路末帧) 即可精确按短的那路裁剪。
 *  3. 静止场景靠 KEY_REPEAT_PREVIOUS_FRAME_AFTER 持续产帧，避免 VFR 造成
 *     接收侧长时间拿不到新帧（实时架构里多种冻结的根因之一）。
 *  4. stop() 会先 signalEndOfInputStream() 再把 EOS 前的所有帧 drain 进 muxer，
 *     保证容器完整、尾部不丢帧（旧实时架构用户反馈“后面一段没保存”）。
 *
 * 典型用法：
 * <pre>
 *   Mp4Recorder r = new Mp4Recorder(path, w, h, fps, bitrate, iframe, repeatUs, log);
 *   r.start();
 *   Surface input = r.inputSurface();     // 交给 Camera2 / SurfaceControl
 *   while (!stopRequested) {
 *       int n = r.drainOnce(200_000L);
 *       ...
 *   }
 *   r.stopAndRelease();                   // 必须调用，否则 mp4 moov 缺失
 * </pre>
 */
public final class Mp4Recorder {

    /** 日志出口：screen 进程写 stdout，camera 服务写 Logcat，由调用方决定。 */
    public interface LogSink {
        void log(String message);
    }

    private static final long TIMEOUT_USEC = 200_000L;   // 200ms

    private final String path;
    private final int width;
    private final int height;
    private final int fps;
    private final int bitrate;
    private final int iframe;
    private final int repeatUs;
    private final LogSink sink;

    private MediaCodec codec;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private boolean eosReceived = false;
    private boolean released = false;

    private long lastPTS = -1;
    private int frameCount = 0;
    private int configCount = 0;
    private long firstPTS = -1;
    private long lastWrittenPTS = -1;

    /** pts 时基起点（开机单调时钟微秒），仅用于日志。 */
    private final long clockStartUs = SystemClock.elapsedRealtimeNanos() / 1000L;

    public Mp4Recorder(String path, int width, int height, int fps,
                       int bitrate, int iframe, int repeatUs, LogSink sink) {
        this.path = path;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.iframe = iframe;
        this.repeatUs = repeatUs;
        this.sink = sink;
    }

    private void log(String s) {
        if (sink != null) sink.log("[FUSION-MP4] " + s);
    }

    /** 创建编码器与 muxer，返回前 inputSurface() 可用。 */
    public void start() throws Exception {
        File out = new File(path);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        // 每次录制覆盖旧文件，避免上次半截 mp4 被误拉取
        if (out.exists()) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }

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
        inputSurface = codec.createInputSurface();
        codec.start();

        // MUXER_OUTPUT_MPEG_4 = 0
        muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        log("started " + width + "x" + height + " @" + fps + "fps " + bitrate
                + "bps iframe=" + iframe + " repeatUs=" + repeatUs + " -> " + path);
    }

    /** 交给 Camera2 createCaptureSession / SurfaceControl.setDisplaySurface 的输入 Surface。 */
    public Surface inputSurface() {
        return inputSurface;
    }

    /**
     * 取一次编码输出并写进 mp4。
     * @return 0 = 本轮无输出；1 = 写入了一帧；2 = 已收到 EOS
     */
    public int drainOnce(long timeoutUs) throws Exception {
        if (released || codec == null) return 2;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int index = codec.dequeueOutputBuffer(info, timeoutUs > 0 ? timeoutUs : TIMEOUT_USEC);

        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (muxerStarted) {
                throw new IllegalStateException("output format changed twice");
            }
            MediaFormat outFormat = codec.getOutputFormat();
            trackIndex = muxer.addTrack(outFormat);
            muxer.start();
            muxerStarted = true;
            log("muxer started, track=" + trackIndex + " format=" + outFormat);
            return 0;
        }
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER
                || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            return 0;
        }
        if (index < 0) return 0;

        ByteBuffer buf = codec.getOutputBuffer(index);
        try {
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                // EOS 帧可能带数据，先把它写掉再标记结束
                writeSample(buf, info);
                eosReceived = true;
                return 2;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // SPS/PPS 已经包含在 addTrack 的 format(csd-0/csd-1) 里，
                // 再单独写一遍会让部分播放器读到重复参数集。
                configCount++;
                return 0;
            }
            writeSample(buf, info);
            return 1;
        } finally {
            codec.releaseOutputBuffer(index, false);
        }
    }

    private void writeSample(ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (!muxerStarted || buf == null || info.size <= 0) return;

        // ★ 离线架构核心：用开机单调时钟覆盖编码器的输出时间戳。
        // MediaMuxer 要求 pts 单调不减；同一微秒到来时强制 +1。
        long pts = SystemClock.elapsedRealtimeNanos() / 1000L;
        if (pts <= lastPTS) pts = lastPTS + 1;
        lastPTS = pts;

        buf.position(info.offset);
        buf.limit(info.offset + info.size);
        MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
        outInfo.set(info.offset, info.size, pts, info.flags);
        muxer.writeSampleData(trackIndex, buf, outInfo);

        if (firstPTS < 0) firstPTS = pts;
        lastWrittenPTS = pts;
        frameCount++;
    }

    /** 通知编码器输入结束，并把残留帧全部 drain 进 muxer。 */
    public void stopAndRelease() {
        if (released || codec == null) return;
        log("stopping: frames=" + frameCount + " configs=" + configCount);
        try {
            // Surface 输入结束时必须用这个方法，不能只 release input surface
            codec.signalEndOfInputStream();
        } catch (Throwable t) {
            log("signalEndOfInputStream failed: " + t);
        }

        long guard = System.currentTimeMillis() + 5000L;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!eosReceived && System.currentTimeMillis() < guard) {
                int index = codec.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                    continue;
                }
                if (index < 0) continue;
                ByteBuffer buf = codec.getOutputBuffer(index);
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        writeSample(buf, info);
                        eosReceived = true;
                    } else if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        writeSample(buf, info);
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false);
                }
            }
            log("drain done eos=" + eosReceived + " frames=" + frameCount);
        } catch (Throwable t) {
            log("drain failed: " + t);
        }

        if (muxerStarted) {
            try { muxer.stop(); } catch (Throwable t) { log("muxer.stop failed: " + t); }
        }
        if (inputSurface != null) {
            try { inputSurface.release(); } catch (Throwable ignored) {}
            inputSurface = null;
        }
        try { codec.stop(); } catch (Throwable ignored) {}
        try { codec.release(); } catch (Throwable ignored) {}
        try { if (muxer != null) muxer.release(); } catch (Throwable ignored) {}
        codec = null;
        muxer = null;
        released = true;

        long ms = lastWrittenPTS > firstPTS && firstPTS > 0
                ? (lastWrittenPTS - firstPTS) / 1000L : 0L;
        File out = new File(path);
        log("DONE file=" + path + " bytes=" + (out.exists() ? out.length() : 0)
                + " frames=" + frameCount + " span=" + ms + "ms");
    }

    public int frames() { return frameCount; }
    public long firstPTSUs() { return firstPTS; }
    public long lastPTSUs() { return lastWrittenPTS; }
    public long clockStartUs() { return clockStartUs; }
    public boolean isEos() { return eosReceived; }
}
