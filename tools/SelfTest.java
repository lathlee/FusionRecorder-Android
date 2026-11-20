package com.fusion.capture;

/**
 * pts SEI 协议的纯 Java 自测（不依赖 Android 运行时，可直接在本机 JVM 跑）。
 * build.py 会自动编译执行它；失败则整个构建中止。
 */
public final class SelfTest {

    private static int passed = 0;

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL: " + what);
        passed++;
    }

    /** 检查 EBSP 里不会出现伪起始码 00 00 01 / 00 00 00 01。 */
    private static void checkNoFakeStartCode(byte[] nal) {
        for (int i = 5; i < nal.length - 2; i++) {
            boolean three = (nal[i] & 0xff) == 0 && (nal[i + 1] & 0xff) == 0
                    && (nal[i + 2] & 0xff) == 1;
            boolean four = i + 3 < nal.length && (nal[i] & 0xff) == 0
                    && (nal[i + 1] & 0xff) == 0 && (nal[i + 2] & 0xff) == 0
                    && (nal[i + 3] & 0xff) == 1;
            check(!three && !four, "NAL 内出现伪起始码 @offset " + i);
        }
    }

    private static void roundTrip(long ptsUs, int streamId, int seq) {
        byte[] nal = PtsSei.build(ptsUs, streamId, seq);
        check(nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1,
                "起始码 00 00 00 01");
        check((nal[4] & 0x1f) == 6, "NAL type == 6 (SEI)");
        checkNoFakeStartCode(nal);
        check(PtsSei.parsePtsUs(nal, streamId) == ptsUs,
                "pts 往返 " + ptsUs + " -> " + PtsSei.parsePtsUs(nal, streamId));
        check(PtsSei.parseSeq(nal) == seq, "seq 往返 " + seq);
        check(PtsSei.parsePtsUs(nal, streamId == 1 ? 2 : 1) == -1,
                "streamId 校验生效");
    }

    public static void main(String[] args) {
        // 覆盖：0、小值、带大量 0x00 字节、接近 long 上限、心跳标记位
        long[] ptsSamples = {
                0L,
                1L,
                123456789L,
                3_600_123_456L,          // 开机 1 小时，前 4 字节全是 0
                0x0000_00FF_0000_00FFL,  // 交替 0x00，最容易触发 EBSP
                0x0102_0300_0405_0607L,  // 含 0x03，检验转义/逆转义
                9_999_999_999_999_999L
        };
        for (long pts : ptsSamples) {
            roundTrip(pts, PtsSei.STREAM_SCREEN, 42);
            roundTrip(pts, PtsSei.STREAM_CAMERA, 0x7FFF_FFFF);
        }

        byte[] hb = PtsSei.build(1234L, PtsSei.STREAM_SCREEN,
                PtsSei.SEQ_HEARTBEAT_FLAG | 7);
        check(PtsSei.isHeartbeat(PtsSei.parseSeq(hb)), "心跳标记位");
        check(!PtsSei.isHeartbeat(PtsSei.parseSeq(
                PtsSei.build(1234L, PtsSei.STREAM_SCREEN, 7))), "普通帧不误判为心跳");
        check(PtsSei.parsePtsUs(new byte[]{0x06, 0x00}, 1) == -1, "坏包被拒");
        check(PtsSei.parsePtsUs(null, 1) == -1, "null 被拒");

        System.out.println("[SELFTEST] 全部通过，断言 " + passed + " 条");
        System.out.println("[SELFTEST] SEI NAL 长度示例 = "
                + PtsSei.build(3_600_123_456L, 1, 42).length + " 字节");
    }
}
