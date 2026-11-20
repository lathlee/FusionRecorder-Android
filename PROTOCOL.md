# 双路 H264 线协议规格（v1）

适用范围：`screen-push.jar`（9114 屏幕）与 `fusion-capture.apk`（9115 相机）→ iPhone / 主机接收端。

**唯一真源**：发送端 `PtsSei.java` / `StreamSender.java`（两条构建链共用）；
接收端参考实现 `tools/stream_probe.py`；本文是两者的自然语言说明。
`build.py` 会在编译前用 Java 端真实生成的字节样本，校验 Python 接收端实现逐字节一致。

---

## 1. 传输层

| 项 | 值 |
|---|---|
| 传输 | 裸 TCP，长连接，`TCP_NODELAY`，发送缓冲 256 KB |
| 屏幕流端口 | **9114**，`streamId = 1` |
| 相机流端口 | **9115**，`streamId = 2` |
| 载荷 | H.264 **Annex-B 裸流**，无容器（无 mp4 / ts）、无 rtp、无长度前缀 |
| 起始码 | `00 00 00 01`（4 字节）或 `00 00 01`（3 字节） |
| 首包 | 编码器 `BUFFER_FLAG_CODEC_CONFIG` 的 SPS+PPS，原样先发，流起始应能看到 `00 00 00 01 67` |
| 终止 | 接收端断开 TCP；采集端心跳最多 1 秒内感知并退出 |
| 顺序 | 采集端保证顺序、不重传；TCP 已保证不丢包，半包/粘包由接收端按起始码切分处理 |

---

## 2. 每个编码帧的发送顺序

```
┌─ 非 config 的每个 MediaCodec 输出 buffer ─────────────────────┐
│  1. [可选] 一条 pts SEI NAL（type 6，见 §3）                   │
│  2. MediaCodec 原始 Annex-B 数据（通常一个 VCL NAL，type 1/5）  │
└───────────────────────────────────────────────────────────────┘
```

- `BUFFER_FLAG_CODEC_CONFIG`（SPS/PPS）走 `writeConfig()`，**不加 SEI**，接收端需要它建
  `VTDecompressionSession` / ffmpeg 解码器。
- 接收端算法：解析到 SEI → 记 `pendingPts` → 下一个 **VCL NAL**（type 1/5）绑定这个 pts；
  心跳 SEI 后面**没有** VCL NAL，只能用来更新时钟，不能当画面帧。
- 已知限制：若某个输出 buffer 含多个 VCL NAL，只有第一个拿到 pts。Android MediaCodec
  在 surface 输入模式下通常一帧一 buffer，实测不影响。

---

## 3. pts SEI NAL 字节规格

采用标准 H.264 **SEI / `user_data_unregistered`**（NAL type 6，payloadType 5），
不是私有魔数塞进视频数据里，所以任何标准解码器都会安全地忽略它。

```
偏移  长度  内容
──────────────────────────────────────────────────────────────────
0     4    起始码 00 00 00 01
4     1    NAL header 0x06   (nal_ref_idc=0, nal_unit_type=6 SEI)
5     1    payloadType 0x05  (user_data_unregistered)
6     1    payloadSize 0x1D  (29，< 255 所以单字节，无需 0xFF 扩展)
7     29   payload（见下表），已做 EBSP 转义
…     1    rbsp_trailing_bits 0x80（同样参与转义输出）
```

### 3.1 payload 的 29 字节（**逆转义之后**的逻辑结构）

| 偏移 | 长度 | 字段 | 说明 |
|---|---|---|---|
| 0 | 16 | UUID | ASCII `"FUSIONPTS1"` (10 B) + `00 00 00 00 00 00` (6 B)，固定魔数 |
| 16 | 8 | `ptsUs` | **8 字节大端无符号**，单位微秒，绝对值 |
| 24 | 1 | `streamId` | `1` = 屏幕流 9114，`2` = 相机流 9115 |
| 25 | 4 | `seq` | 4 字节大端帧序号，从 0 递增；**最高位 = 1 表示心跳**（后面没有画面帧） |

### 3.2 时间基准

`ptsUs = android.os.SystemClock.elapsedRealtimeNanos() / 1000`，即 Linux
`CLOCK_MONOTONIC` 自开机以来的微秒**绝对值**。

- 屏幕流（shell uid 的 `app_process`）和相机流（App uid 的 Service）是两个独立进程，
  但共享系统级单调时钟，**两路 pts 可以直接相减对齐**，不需要协商基准；
- 不受 NTP 校时、用户改系统时间影响；
- `ptsUs` 在 `writeFrame()` 调用时刻取，即该帧被写进 TCP 的时刻（采集时刻的近似，误差 < 一帧）。

### 3.3 EBSP 转义（必须做）

对 `payloadType + payloadSize + payload + 0x80` 这段 RBSP 做标准
**emulation prevention**：连续出现两个 `0x00` 后，若下一个字节 ≤ `0x03`，
先插入一个 `0x03`。这保证 SEI 内部**永远不会出现伪起始码** `00 00 01`。

接收端必须先逆转义（连续两个 `0x00` 后的那个 `0x03` 直接丢弃）再解析 payload。

真实样本（`ptsUs=3600123456, streamId=1, seq=42`，含起始码共 42 字节）：

```
0000000106051d465553494f4e505453310000030000030000030000030000d695864001000003002a80
│      ││││ │                              │               │ │ │       │  │
│      ││││ └─ UUID "FUSIONPTS1"+6 个 0（被 03 打断）        │ │ │       │  └─ 0x80 trailing
│      │││└── payloadSize 0x1D                              │ │ │       └──── seq=42
│      ││└─── payloadType 0x05                              │ │ └──────────── streamId=1
│      │└──── NAL header 0x06                               │ └────────────── pts 0x000000d6958640
│      └───── 起始码                                          └─ 0x03 转义字节
```

注意 `ptsUs` 的大端编码里也可能有前导 `0x00`，同样会被 `0x03` 打断——所以
**一定要逆转义**，不能直接按偏移读原始字节。

### 3.4 心跳

当某路超过 1 秒（`PtsSei.HEARTBEAT_IDLE_US`）没有真实帧，采集端发一条
`seq = 0x80000000 | tick` 的 SEI，**后面不跟任何 VCL NAL**。用途：

1. 屏幕静止时编码器不产帧，心跳让接收端仍能确认连接存活、对齐时钟；
2. 接收端断开 TCP 后，采集端下一次心跳写失败，最多 1 秒内退出；
3. 旧接收端按 type 6 忽略，完全无感。

接收端看到 `seq & 0x80000000 != 0` 就判为心跳：**更新时钟，但不要刷新画面、不要计帧**。

---

## 4. 向后兼容（重要）

iPhone 端 v0.1.2 的 `H264FrameDecoder.swift` 按 Annex-B 起始码切 NAL，逻辑是：

```swift
switch header & 0x1f {
case 7: sps = nal
case 8: pps = nal
case 5, 1: /* 解码 */
default: break        // ← type 6 SEI 落到这里，直接忽略
}
```

所以：

- 新增的 SEI 会被当作普通 NAL 切出来，然后被 `default` 丢弃，**不送进 VideoToolbox**；
- SEI 做了 EBSP 转义，不会伪造出起始码把视频数据切坏；
- **不升级 iPhone 端也能正常收流、解码、抓帧**，只是拿不到真实 pts（继续按帧数近似）。

要拿到 pts，只需在 `onNAL` 回调最前面加下面这段解析，不需要改动传输层。

---

## 5. 接收端解析（可直接粘进 iOS）

### 5.1 Swift（放在 `H264FrameDecoder` / `RecorderSession` 里）

```swift
private let fusionPTSUUID: [UInt8] = Array("FUSIONPTS1".utf8) + [0, 0, 0, 0, 0, 0]
private var pendingPTSus: UInt64?
private var lastHeartbeatUs: UInt64?

/// 解析一条 SEI NAL；返回 (ptsUs, seq)。不是本协议的 SEI 返回 nil。
func parseFusionPTS(_ nal: [UInt8]) -> (UInt64, UInt32)? {
    // 1) 跳过起始码（本工程的 receiver 已剥离起始码，这里仍兼容未剥离的情况）
    var off = 0
    while off < nal.count && nal[off] == 0 { off += 1 }
    if off < nal.count, nal[off] == 1 { off += 1 } else { off = 0 }
    guard off < nal.count, (nal[off] & 0x1f) == 6 else { return nil }

    // 2) EBSP 逆转义：连续两个 0x00 之后的 0x03 直接丢弃
    var rbsp: [UInt8] = []
    var zeros = 0
    for v in nal[(off + 1)...] {
        if zeros >= 2 && v == 3 { zeros = 0; continue }
        rbsp.append(v)
        zeros = (v == 0) ? zeros + 1 : 0
    }
    guard rbsp.count >= 2 + 29 + 1, rbsp[0] == 5, rbsp[1] == 29 else { return nil }

    // 3) 校验魔数
    var p = 2
    guard Array(rbsp[p..<(p + 16)]) == fusionPTSUUID else { return nil }
    p += 16
    var pts: UInt64 = 0
    for _ in 0..<8 { pts = (pts << 8) | UInt64(rbsp[p]); p += 1 }
    let sid = rbsp[p]; p += 1
    guard sid == 1 || sid == 2 else { return nil }
    let seq = (UInt32(rbsp[p]) << 24) | (UInt32(rbsp[p + 1]) << 16)
            | (UInt32(rbsp[p + 2]) << 8) | UInt32(rbsp[p + 3])
    return (pts, seq)
}

// 在 H264StreamReceiver 的 onNAL 回调最前面：
func handleNAL(_ data: Data) {
    let bytes = [UInt8](data)
    guard let first = bytes.first else { return }
    if first & 0x1f == 6 {
        if let (pts, seq) = parseFusionPTS(bytes) {
            if seq & 0x80000000 != 0 {
                lastHeartbeatUs = pts          // 心跳：只更新时钟，不刷新画面、不计帧
            } else {
                pendingPTSus = pts             // 下一个 VCL NAL 用这个 pts
            }
        }
        return                                  // SEI 绝不送进解码器
    }
    // ...原有 SPS/PPS/VCL 处理；VCL 解码成 CGImage 后：
    // let t = CMTime(value: CMTimeValue(pts - baseUs), timescale: 1_000_000)
    // pendingPTSus = nil
}
```

### 5.2 Python / Java

- Python：`tools/stream_probe.py` 的 `build_sei()` / `parse_sei()` / `unescape()` / `NalSplitter`
  （`python3 tools/stream_probe.py selftest` 会与 Java 端真实字节做逐字节比对）。
- Java：`PtsSei.parsePtsUs()` / `parseSeq()`（`build.py` 的 `SelfTest.java` 覆盖 565 条断言）。

---

## 6. 两路对齐与成片时间轴

两路帧率天然不同（屏幕 ~20fps、相机 ~30fps），且屏幕流可能 VFR，**不要按帧号硬对齐**。

推荐做法（与交接包 `compose_poc.py` 的「最近帧映射到统一 CFR 时间轴」一致，但换成真实 pts）：

1. 取 `t0 = min(两路首帧 ptsUs)` 作为成片零点；
2. 每路维护 `(ptsUs, CGImage)` 队列，按 `ptsUs` 升序；
3. 目标成片按固定帧率（如 30fps）生成输出时刻 `T(k) = t0 + k * 33333us`；
4. 对每个 `T(k)`，在两路队列里各取 **pts 不晚于 T(k) 的最后一张帧**，交给
   `FusionCompositor.render(screen:camera:params:)`；
5. 用 `CMTime(value: ptsUs - t0, timescale: 1_000_000)` 写进 `AVAssetWriter`。

这样静止画面会自然重复上一帧，不会出现「按帧数均匀铺」造成的时间漂移；
预览与成片仍然共用同一个 `FusionParams` 和同一个合成函数，参数效果不会走样。

---

## 7. 已知限制

- 一个输出 buffer 含多个 VCL NAL 时只有首个 NAL 带 pts（实测未出现）。
- `ptsUs` 是「写入 TCP 时刻」而非传感器曝光时刻，误差 < 一帧；对融合录屏足够。
- 裸 TCP 无重传与纠错；无线链路抖动由 TCP 自行处理，但长录建议 iPhone 与眼镜贴近同一热点。
- `sec=0` 长录时屏幕流进程靠心跳检测断开，最多 1 秒延迟退出。
- SEI 是 `user_data_unregistered`，某些转封装工具会剥离 SEI；**不要**把流先过 muxer 再解析 pts。
