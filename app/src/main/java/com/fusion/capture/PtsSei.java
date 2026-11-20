package com.fusion.capture;

import java.io.ByteArrayOutputStream;

/**
 * 双路 H264 流的 pts 封装（交接包 §7 待办 1）。
 *
 * 设计目标：向后兼容。pts 走 H.264 标准 SEI / user_data_unregistered NAL（type 6），
 * 插在对应编码帧的所有 NAL 之前。旧接收端按 Annex-B 起始码切 NAL 时，
 * iPhone v0.1.2 的 H264FrameDecoder 对 type 6 走 default 分支直接忽略，
 * 因此不升级 iOS 端也不会坏；新接收端解析 SEI 即可拿到真实时间戳。
 *
 * NAL 结构（含起始码）：
 *   <pre>
 *   00 00 00 01 06 <EBSP(rbsp)>
 *
 *   rbsp = payloadType(0x05)
 *        + payloadSize(0x1D = 29)
 *        + payload[29]:
 *            [ 0..15] UUID   "FUSIONPTS1" + 6 个 0x00（固定魔数）
 *            [16..23] ptsUs  8 字节大端，单调时钟微秒（绝对开机时间）
 *            [24]     streamId 1=屏幕流(9114) / 2=相机流(9115)
 *            [25..28] seq     4 字节大端帧序号；最高位为 1 表示心跳（无画面帧）
 *        + rbsp_trailing_bits(0x80)
 *   </pre>
 *
 * EBSP = 对 rbsp 做 emulation prevention（连续两个 0x00 后遇 0x00..0x03 插入 0x03），
 * 保证 payload 里永远不出现伪起始码 00 00 01。接收端必须先逆转义再解析。
 *
 * 时间基准：{@link android.os.SystemClock#elapsedRealtimeNanos()} / 1000，
 * 即 CLOCK_MONOTONIC 单调微秒。屏幕流（shell uid 的 app_process）与相机流（App uid 的
 * Service）是两个独立进程，但共享同一系统级单调时钟，所以两路 pts 可直接相减对齐，
 * 不需要协商基准、不受 NTP 校时影响。
 *
 * 这个类被 APK 与 screen-push.jar 两条构建链同时编译，是 pts 编码的唯一真源。
 */
public final class PtsSei {

    public static final int STREAM_SCREEN = 1;
    public static final int STREAM_CAMERA = 2;

    /** 心跳帧标记：seq 最高位置 1，表示这一条 SEI 后面没有视频帧。 */
    public static final int SEQ_HEARTBEAT_FLAG = 0x80000000;
    /** 心跳触发阈值：超过这个时间没有真实帧输出就发一条带时间戳的 SEI 保活。 */
    public static final long HEARTBEAT_IDLE_US = 1_000_000L;

    private static final byte[] UUID = {
            'F', 'U', 'S', 'I', 'O', 'N', 'P', 'T', 'S', '1',
            0, 0, 0, 0, 0, 0
    };

    private static final int UUID_LEN = 16;
    private static final int PAYLOAD_LEN = UUID_LEN + 8 + 1 + 4; // 29
    private static final int PAYLOAD_TYPE_USER_DATA_UNREGISTERED = 0x05;
    private static final byte RBSP_TRAILING = (byte) 0x80;
    private static final byte[] START_CODE = {0, 0, 0, 1};
    private static final byte NAL_HEADER_SEI = 0x06; // nal_ref_idc=0, type=6

    private PtsSei() {
    }

    /**
     * 构造一条完整的 pts SEI NAL（含 4 字节起始码），可直接写进裸 TCP 流。
     *
     * @param ptsUs    单调时钟微秒绝对值
     * @param streamId {@link #STREAM_SCREEN} 或 {@link #STREAM_CAMERA}
     * @param seq      帧序号；心跳请传 {@code SEQ_HEARTBEAT_FLAG | tick}
     */
    public static byte[] build(long ptsUs, int streamId, int seq) {
        // rbsp = type + size + payload + trailing
        byte[] rbsp = new byte[2 + PAYLOAD_LEN + 1];
        int i = 0;
        rbsp[i++] = (byte) PAYLOAD_TYPE_USER_DATA_UNREGISTERED;
        rbsp[i++] = (byte) PAYLOAD_LEN;
        System.arraycopy(UUID, 0, rbsp, i, UUID_LEN);
        i += UUID_LEN;
        for (int b = 7; b >= 0; b--) {
            rbsp[i++] = (byte) (ptsUs >>> (b * 8));
        }
        rbsp[i++] = (byte) streamId;
        rbsp[i++] = (byte) (seq >>> 24);
        rbsp[i++] = (byte) (seq >>> 16);
        rbsp[i++] = (byte) (seq >>> 8);
        rbsp[i++] = (byte) seq;
        rbsp[i++] = RBSP_TRAILING;

        byte[] ebsp = escape(rbsp);
        byte[] nal = new byte[START_CODE.length + 1 + ebsp.length];
        System.arraycopy(START_CODE, 0, nal, 0, START_CODE.length);
        nal[START_CODE.length] = NAL_HEADER_SEI;
        System.arraycopy(ebsp, 0, nal, START_CODE.length + 1, ebsp.length);
        return nal;
    }

    /** emulation prevention：00 00 后接 00/01/02/03 时插入 0x03。 */
    static byte[] escape(byte[] rbsp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rbsp.length + 16);
        int zeros = 0;
        for (byte value : rbsp) {
            int v = value & 0xff;
            if (zeros >= 2 && v <= 0x03) {
                out.write(0x03);
                zeros = 0;
            }
            out.write(v);
            if (v == 0) {
                zeros++;
            } else {
                zeros = 0;
            }
        }
        return out.toByteArray();
    }

    /** 逆转义，返回从 NAL header 之后开始的 RBSP（不含 trailing 语义处理）。 */
    static byte[] unescape(byte[] nal, int skip) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (int i = skip; i < nal.length; i++) {
            int v = nal[i] & 0xff;
            if (zeros >= 2 && v == 0x03) {
                zeros = 0; // 丢掉 emulation prevention byte
                continue;
            }
            out.write(v);
            if (v == 0) {
                zeros++;
            } else {
                zeros = 0;
            }
        }
        return out.toByteArray();
    }

    /**
     * 接收端既能拿到「含起始码」也能拿到「已剥离起始码」的 NAL，这里统一识别：
     * 跳过前导 0x00 与紧随其后的 0x01，返回 NAL header 的下标；没有起始码则返回 0。
     */
    private static int skipStartCode(byte[] nal) {
        int i = 0;
        while (i < nal.length && nal[i] == 0) i++;
        if (i < nal.length && (nal[i] & 0xff) == 1) return i + 1;
        return 0;
    }

    /**
     * 接收端参考实现：从一条 SEI NAL 里取出 pts（含或不含起始码都行）。
     * 校验 NAL type / payloadType / payloadSize / UUID / streamId，任一不符返回 -1。
     * iOS / Python 侧照此实现即可，见 PROTOCOL.md。
     */
    public static long parsePtsUs(byte[] nal, int expectedStreamId) {
        if (nal == null) return -1;
        int off = skipStartCode(nal);
        if (nal.length < off + 1 + 2 + PAYLOAD_LEN) return -1;
        if ((nal[off] & 0x1f) != 6) return -1;             // nal_unit_type == SEI
        byte[] rbsp = unescape(nal, off + 1);               // 跳过 NAL header 再逆转义
        if (rbsp.length < 2 + PAYLOAD_LEN + 1) return -1;
        if ((rbsp[0] & 0xff) != PAYLOAD_TYPE_USER_DATA_UNREGISTERED) return -1;
        if ((rbsp[1] & 0xff) != PAYLOAD_LEN) return -1;
        int p = 2;
        for (int k = 0; k < UUID_LEN; k++) {
            if (rbsp[p + k] != UUID[k]) return -1;
        }
        p += UUID_LEN;
        long pts = 0;
        for (int k = 0; k < 8; k++) {
            pts = (pts << 8) | (rbsp[p + k] & 0xffL);
        }
        p += 8;
        int sid = rbsp[p] & 0xff;
        if (expectedStreamId > 0 && sid != expectedStreamId) return -1;
        return pts;
    }

    /** 接收端参考实现：取出 seq（含心跳标记位）。 */
    public static int parseSeq(byte[] nal) {
        if (nal == null) return -1;
        int off = skipStartCode(nal);
        if (nal.length < off + 1 + 2 + PAYLOAD_LEN) return -1;
        if ((nal[off] & 0x1f) != 6) return -1;
        byte[] rbsp = unescape(nal, off + 1);
        int p = 2 + UUID_LEN + 8 + 1;
        if (rbsp.length < p + 4) return -1;
        return ((rbsp[p] & 0xff) << 24) | ((rbsp[p + 1] & 0xff) << 16)
                | ((rbsp[p + 2] & 0xff) << 8) | (rbsp[p + 3] & 0xff);
    }

    public static boolean isHeartbeat(int seq) {
        return seq != -1 && (seq & SEQ_HEARTBEAT_FLAG) != 0;
    }
}
