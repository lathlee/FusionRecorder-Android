#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
融合采集 · 双路流接收探针（iPhone / 主机侧参考实现）

两个用途：

1) selftest —— 不需要眼镜，纯本机验证「接收端解析」与「眼镜端编码」字节级一致。
   内置三条由 Java 端 PtsSei.build() 实际生成的 SEI 十六进制样本，
   用本文件的 Python 实现重新编码，断言逐字节相同；再把两路假流喂给
   NAL 切分器，断言 pts / streamId / seq / 心跳 / 两路时钟对齐都正确。
   这是 PROTOCOL.md 的可执行版本，iPhone 端照此移植 Swift 即可。

2) listen —— 真机联调时替代 iPhone 端接收两路流：
     python3 tools/stream_probe.py listen --seconds 10 --save-prefix /tmp/dual
   监听 9114（屏幕）/ 9115（相机），按 Annex-B 起始码切 NAL、解析 SEI pts、
   统计帧率与两路时间差，并可选把原始 H264 存成文件给 ffmpeg 解码。

用法：
    python3 tools/stream_probe.py selftest
    python3 tools/stream_probe.py listen --screen-port 9114 --camera-port 9115 --seconds 10
"""

import argparse
import socket
import struct
import sys
import threading
import time

UUID = b"FUSIONPTS1" + b"\x00" * 6
PAYLOAD_TYPE = 0x05
PAYLOAD_LEN = 29
STREAM_SCREEN = 1
STREAM_CAMERA = 2
SEQ_HEARTBEAT_FLAG = 0x80000000

# 由 Java 端 PtsSei.build() 直接打印出来的真实字节（tools/SelfTest.java 同族样本）
# 格式：(ptsUs, streamId, seq, hex含起始码)
JAVA_SAMPLES = [
    (3600123456, STREAM_SCREEN, 42,
     "0000000106051d465553494f4e505453310000030000030000030000030000d695864001000003002a80"),
    (72623842593670663, STREAM_SCREEN, 42,
     "0000000106051d465553494f4e50545331000003000003000003010203000405060701000003002a80"),
    (0, STREAM_SCREEN, 42,
     "0000000106051d465553494f4e5054533100000300000300000300000300000300000300000301000003002a80"),
]


def escape(rbsp: bytes) -> bytes:
    """emulation prevention，与 PtsSei.java escape() 逐行等价。"""
    out = bytearray()
    zeros = 0
    for v in rbsp:
        if zeros >= 2 and v <= 0x03:
            out.append(0x03)
            zeros = 0
        out.append(v)
        zeros = zeros + 1 if v == 0 else 0
    return bytes(out)


def unescape(ebsp: bytes) -> bytes:
    """逆转义，与 PtsSei.java unescape() 逐行等价。"""
    out = bytearray()
    zeros = 0
    for v in ebsp:
        if zeros >= 2 and v == 0x03:
            zeros = 0
            continue
        out.append(v)
        zeros = zeros + 1 if v == 0 else 0
    return bytes(out)


def build_sei(pts_us: int, stream_id: int, seq: int) -> bytes:
    """Python 版 SEI 构造，用于与 Java 端字节级比对。"""
    rbsp = bytearray([PAYLOAD_TYPE, PAYLOAD_LEN])
    rbsp += UUID
    rbsp += struct.pack(">Q", pts_us & 0xFFFFFFFFFFFFFFFF)
    rbsp += bytes([stream_id & 0xFF])
    rbsp += struct.pack(">I", seq & 0xFFFFFFFF)
    rbsp.append(0x80)  # rbsp_trailing_bits
    return b"\x00\x00\x00\x01\x06" + escape(bytes(rbsp))


def parse_sei(nal: bytes, expected_stream: int = 0):
    """
    解析一条 SEI NAL（含或不含起始码都行）。
    返回 dict(ptsUs, streamId, seq, heartbeat)，不符合本协议返回 None。
    """
    if not nal:
        return None
    i = 0
    while i < len(nal) and nal[i] == 0:
        i += 1
    off = i + 1 if (i < len(nal) and nal[i] == 1) else 0
    if len(nal) < off + 1 + 2 + PAYLOAD_LEN:
        return None
    if (nal[off] & 0x1F) != 6:
        return None
    rbsp = unescape(nal[off + 1:])
    if len(rbsp) < 2 + PAYLOAD_LEN + 1:
        return None
    if rbsp[0] != PAYLOAD_TYPE or rbsp[1] != PAYLOAD_LEN:
        return None
    p = 2
    if rbsp[p:p + 16] != UUID:
        return None
    p += 16
    pts = int.from_bytes(rbsp[p:p + 8], "big")
    p += 8
    sid = rbsp[p]
    p += 1
    seq = int.from_bytes(rbsp[p:p + 4], "big", signed=False)
    if seq & SEQ_HEARTBEAT_FLAG:
        seq = seq - (1 << 32) if seq >= (1 << 31) else seq
    if expected_stream and sid != expected_stream:
        return None
    return {"ptsUs": pts, "streamId": sid, "seq": seq,
            "heartbeat": bool(int.from_bytes(rbsp[p:p + 4], "big") & SEQ_HEARTBEAT_FLAG)}


# ---------------------------------------------------------------- NAL 切分

class NalSplitter:
    """按 Annex-B 起始码切 NAL，处理 TCP 半包 / 粘包（与 iOS H264StreamReceiver 同思路）。"""

    def __init__(self, on_nal, on_stream_bytes=None):
        self.buf = bytearray()
        self.on_nal = on_nal
        self.on_stream_bytes = on_stream_bytes

    def _find(self, data, start=0):
        for i in range(start, len(data) - 2):
            if data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 1:
                # 前面若还有一个 0，按 4 字节起始码算
                return (i - 1, 4) if i > 0 and data[i - 1] == 0 else (i, 3)
        return None

    def feed(self, chunk: bytes):
        if self.on_stream_bytes:
            self.on_stream_bytes(chunk)
        self.buf.extend(chunk)
        while True:
            first = self._find(self.buf)
            if first is None:
                # 没有任何起始码：只保留末尾两个可能是起始码前缀的 0
                keep = 0
                while keep < 2 and len(self.buf) > keep and self.buf[-1 - keep] == 0:
                    keep += 1
                if len(self.buf) > keep:
                    del self.buf[:len(self.buf) - keep]
                return
            start, code_len = first
            if start > 0:
                del self.buf[:start]  # 丢掉起始码之前的垃圾字节
            nxt = self._find(self.buf, code_len)
            if nxt is None:
                return  # 等下一个起始码出现才能确定当前 NAL 的边界
            nal = bytes(self.buf[code_len:nxt[0]])
            if nal:
                self.on_nal(nal)
            del self.buf[:nxt[0]]

    def flush(self):
        first = self._find(self.buf)
        if first is None:
            return
        start, code_len = first
        nal = bytes(self.buf[start + code_len:])
        if nal:
            self.on_nal(nal)
        self.buf.clear()


# ---------------------------------------------------------------- 真机接收

class Stats:
    def __init__(self, name, stream_id, align):
        self.name = name
        self.stream_id = stream_id
        self.align = align
        self.pending_pts = None
        self.pending_seq = None
        self.frames = 0
        self.configs = 0
        self.heartbeats = 0
        self.bytes = 0
        self.sps = False
        self.pps = False
        self.start_wall = time.time()
        self.first_pts = None
        self.last_pts = None

    def on_nal(self, nal):
        self.bytes += len(nal)
        t = nal[0] & 0x1F
        if t == 6:
            info = parse_sei(nal, self.stream_id)
            if info:
                if info["heartbeat"]:
                    self.heartbeats += 1
                else:
                    self.pending_pts = info["ptsUs"]
                    self.pending_seq = info["seq"]
            return
        if t == 7:
            self.sps = True
            self.configs += 1
            return
        if t == 8:
            self.pps = True
            self.configs += 1
            return
        if t in (1, 5):
            self.frames += 1
            pts = self.pending_pts
            if pts is not None:
                if self.first_pts is None:
                    self.first_pts = pts
                self.last_pts = pts
                self.align.report(self.stream_id, pts, self.name)
            self.pending_pts = None

    def line(self):
        dur = time.time() - self.start_wall
        fps = self.frames / dur if dur > 0 else 0
        span = ((self.last_pts - self.first_pts) / 1000.0
                if self.first_pts is not None and self.last_pts is not None else 0)
        return ("[%-6s] frames=%-5d fps=%5.1f config=%d(SPS=%s/PPS=%s) "
                "heartbeat=%-4d bytes=%-9d ptsSpan=%.0fms"
                % (self.name, self.frames, fps, self.configs,
                   "Y" if self.sps else "N", "Y" if self.pps else "N",
                   self.heartbeats, self.bytes, span))


class Align:
    """
    两路共用单调时钟，按「时间戳不晚于相机帧的最后一张屏幕帧」配对。

    重要：两路在不同线程，TCP **到达顺序不等于采集时间顺序**。所以这里只缓存 pts，
    在 summary() 里把两路各自按时间戳排序后用双指针配对 —— 这正是 PROTOCOL.md §6
    推荐给 iPhone 端的做法（先缓存、按 pts 升序、再取锚点帧），
    不能「收到一帧就立刻拿另一路的 last 帧」，否则会偶发错配一帧。
    """

    MAX_PER_STREAM = 20000

    def __init__(self):
        self.lock = threading.Lock()
        self.screen_pts = []
        self.camera_pts = []
        self.deltas = []

    def report(self, stream_id, pts, name):
        with self.lock:
            target = self.screen_pts if stream_id == STREAM_SCREEN else self.camera_pts
            target.append(pts)
            if len(target) > self.MAX_PER_STREAM:
                del target[:len(target) - self.MAX_PER_STREAM]

    def recompute(self):
        """离线按 pts 排序后配对，与到达顺序无关。"""
        with self.lock:
            ss = sorted(self.screen_pts)
            cs = sorted(self.camera_pts)
            deltas = []
            j = 0
            for c in cs:
                if not ss:
                    break
                while j + 1 < len(ss) and ss[j + 1] <= c:
                    j += 1
                ref = ss[j] if ss[j] <= c else ss[0]
                deltas.append((c - ref) / 1000.0)
            self.deltas = deltas
            return list(deltas)

    def summary(self):
        self.recompute()
        ds = sorted(self.deltas)
        if not ds:
            return "两路配对样本 0 条（其中一路没帧？）"
        return ("两路配对 %d 条，相机帧 - 时间锚点屏幕帧：min=%.1fms "
                "median=%.1fms max=%.1fms（同源单调时钟，可直接对齐）"
                % (len(ds), ds[0], ds[len(ds) // 2], ds[-1]))


def serve_port(port, name, stream_id, align, seconds, save_path, stop_evt):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(1)
    srv.settimeout(1.0)
    print("[%s] 监听 0.0.0.0:%d ..." % (name, port), flush=True)
    fh = open(save_path, "wb") if save_path else None
    stats = Stats(name, stream_id, align)
    splitter = NalSplitter(stats.on_nal, fh.write if fh else None)
    conn = None
    try:
        while not stop_evt.is_set():
            try:
                conn, addr = srv.accept()
                break
            except socket.timeout:
                continue
        if conn is None:
            return
        print("[%s] 采集端已连接 %s:%d" % (name, addr[0], addr[1]), flush=True)
        conn.settimeout(0.5)
        deadline = time.time() + seconds if seconds > 0 else None
        while not stop_evt.is_set():
            if deadline and time.time() > deadline:
                break
            try:
                chunk = conn.recv(64 * 1024)
            except socket.timeout:
                continue
            if not chunk:
                break
            splitter.feed(chunk)
        splitter.flush()
        conn.close()
    except Exception as e:
        print("[%s] 接收异常: %s" % (name, e), flush=True)
    finally:
        if fh:
            fh.close()
        srv.close()
        print(stats.line(), flush=True)
        print("[%s] 连接结束" % name, flush=True)


def cmd_listen(args):
    align = Align()
    stop = threading.Event()
    threads = []
    for port, name, sid, path in [
        (args.screen_port, "screen", STREAM_SCREEN,
         args.save_prefix + "_screen.h264" if args.save_prefix else None),
        (args.camera_port, "camera", STREAM_CAMERA,
         args.save_prefix + "_camera.h264" if args.save_prefix else None),
    ]:
        t = threading.Thread(target=serve_port,
                             args=(port, name, sid, align, args.seconds, path, stop),
                             daemon=True)
        t.start()
        threads.append(t)
    try:
        last = time.time()
        while any(t.is_alive() for t in threads):
            time.sleep(0.5)
            if time.time() - last >= 3:
                print("... 等待两路数据（Ctrl-C 提前结束）", flush=True)
                last = time.time()
    except KeyboardInterrupt:
        print("\n手动结束", flush=True)
        stop.set()
    print("\n" + align.summary(), flush=True)
    if args.save_prefix:
        print("原始流已保存：%s_screen.h264 / %s_camera.h264"
              % (args.save_prefix, args.save_prefix), flush=True)
        print("可用 ffmpeg 解码核对：ffmpeg -i %s_screen.h264 -frames:v 1 /tmp/screen.png"
              % args.save_prefix, flush=True)


def cmd_selftest(_args):
    ok = 0

    def check(cond, what):
        nonlocal ok
        assert cond, "FAIL: " + what
        ok += 1

    # 1) 跨语言字节级一致性：Python 重建 == Java PtsSei.build() 的真实输出
    for pts, sid, seq, hexstr in JAVA_SAMPLES:
        java_bytes = bytes.fromhex(hexstr)
        py_bytes = build_sei(pts, sid, seq)
        check(py_bytes == java_bytes,
              "Java/Python SEI 字节一致 pts=%d (%s)" % (pts, hexstr[:20]))
        info = parse_sei(java_bytes, sid)
        check(info is not None, "Java 样本可被 Python 解析 pts=%d" % pts)
        check(info["ptsUs"] == pts, "pts 解析 %d" % pts)
        check(info["streamId"] == sid, "streamId 解析 %d" % sid)
        check(info["seq"] == seq, "seq 解析 %d" % seq)
        check(parse_sei(java_bytes, 2) is None, "错误 streamId 被拒 pts=%d" % pts)
        ebsp = java_bytes[5:]
        check(b"\x00\x00\x01" not in ebsp and b"\x00\x00\x00\x01" not in ebsp,
              "EBSP 内无伪起始码 pts=%d" % pts)

    # 2) 心跳标记
    hb = build_sei(1234, STREAM_SCREEN, SEQ_HEARTBEAT_FLAG | 7)
    check(parse_sei(hb)["heartbeat"] is True, "心跳标记识别")
    check(parse_sei(build_sei(1234, STREAM_SCREEN, 7))["heartbeat"] is False,
          "普通帧不误判为心跳")

    # 3) 拼接一条双路假流，走真实 NalSplitter + Stats，验证半包/粘包与时钟对齐
    align = Align()
    s_stat = Stats("screen", STREAM_SCREEN, align)
    c_stat = Stats("camera", STREAM_CAMERA, align)
    fake_sps = b"\x00\x00\x00\x01\x67\x42\xc0\x1e\xda\x01"
    fake_pps = b"\x00\x00\x00\x01\x68\xce\x06\xe2"
    fake_vcl = b"\x00\x00\x01\x65\x88\x84\x21\xa0"  # 3 字节起始码也要能切

    s_stream = fake_sps + fake_pps
    c_stream = fake_sps + fake_pps
    base = 3_600_000_000
    for k in range(20):
        s_pts = base + k * 50_000
        c_pts = base + k * 50_000 + 8_000   # 相机比屏幕晚 8ms
        s_stream += build_sei(s_pts, STREAM_SCREEN, k) + fake_vcl
        c_stream += build_sei(c_pts, STREAM_CAMERA, k) + fake_vcl
    s_stream += build_sei(base + 1_000_000, STREAM_SCREEN,
                          SEQ_HEARTBEAT_FLAG | 0)  # 尾部心跳

    # 故意把每条流切成 7 字节的小片喂进去，模拟 TCP 半包
    for st, stream in ((s_stat, s_stream), (c_stat, c_stream)):
        sp = NalSplitter(st.on_nal)
        for i in range(0, len(stream), 7):
            sp.feed(stream[i:i + 7])
        sp.flush()

    check(s_stat.frames == 20, "屏幕流切出 20 帧，实际 %d" % s_stat.frames)
    check(c_stat.frames == 20, "相机流切出 20 帧，实际 %d" % c_stat.frames)
    check(s_stat.sps and s_stat.pps and c_stat.sps and c_stat.pps,
          "SPS/PPS 都被识别")
    check(s_stat.heartbeats == 1, "尾部心跳被识别")
    align.recompute()   # 按 pts 排序后离线配对，不受两路到达顺序影响
    check(len(align.deltas) == 20, "两路配对 20 条，实际 %d" % len(align.deltas))
    check(all(abs(d - 8.0) < 0.001 for d in align.deltas),
          "两路时间差恒为 8ms（同源单调时钟）")
    check(parse_sei(b"\x00\x00\x00\x01\x06\x00") is None, "坏包被拒")

    print("[SELFTEST] 全部通过，断言 %d 条" % ok)
    print("[SELFTEST] " + align.summary())
    print("[SELFTEST] SEI NAL 长度 = %d 字节（含 4 字节起始码）"
          % len(build_sei(3_600_123_456, 1, 42)))
    print("[SELFTEST] 结论：眼镜端 Java 编码 与 iPhone/主机侧 Python 解析 字节级一致")


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("selftest", help="本机验证协议一致性（不需要眼镜）")
    lp = sub.add_parser("listen", help="真机联调：监听两路 TCP 流")
    lp.add_argument("--screen-port", type=int, default=9114)
    lp.add_argument("--camera-port", type=int, default=9115)
    lp.add_argument("--seconds", type=float, default=0,
                    help="收够多少秒后结束，0 表示一直等到对端断开或 Ctrl-C")
    lp.add_argument("--save-prefix", default=None,
                    help="保存两路原始 H264 的文件名前缀，如 /tmp/dual")
    args = p.parse_args()
    if args.cmd == "selftest":
        cmd_selftest(args)
    else:
        cmd_listen(args)


if __name__ == "__main__":
    main()
