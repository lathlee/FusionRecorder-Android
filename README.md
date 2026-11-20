# 融合录屏 · 安卓眼镜采集端

> 这是**眼镜上的采集端 App**，不是手机端、也不是接收端。
> 它只负责把两路真实画面编码成 H264 并通过裸 TCP 送出去；
> 合成、预览、录成 mp4 都在 iPhone 端（`FusionRecorder-iOS`，独立仓库）完成。

---

## 1. 为什么必须是「两个部署单元」

交接包里最关键的实测结论：**这台未 root 的 goolton ARGlasses 上，抓屏和开相机需要两种不同的身份**。

| 流 | 端口 | 运行身份 | 原因 |
|---|---|---|---|
| 眼镜屏幕 | **9114** | `shell` uid=2000，纯 `app_process` dex | App uid 抓不到屏幕帧；`SurfaceControl.createDisplay` 返回 null；`MediaProjection` 授权界面被 ROM 裁掉 |
| 眼镜相机 | **9115** | 已安装 APK 的 App uid | `shell` uid 没有 `CAMERA` 权限：Camera1 会 native abort，Camera2 抛 `SecurityException` |

所以一个 APK 解决不了，必须是：

```
眼镜屏幕 ── shell uid 虚拟屏镜像 + MediaCodec ──> H264 ── 裸 TCP 9114 ──┐
                                                                        ├─> iPhone 合成 → 成片
眼镜相机 ── App uid Camera2 + MediaCodec ────────> H264 ── 裸 TCP 9115 ──┘
```

无线 ADB 只用来**启动 / 授权 / 推 jar / 保活**，视频流不挤进 adb 协议。

这套链路在交接包的 Windows 主机 PoC 上已经逐段实测跑通（10 秒双路并发、200 帧成片）；
本工程把当时散落的 PoC 代码整理成可长期联调的正式采集端，并补齐了交接包 §7 的待办。

---

## 2. 交付物

| 文件 | 说明 |
|---|---|
| `fusion-capture.apk` | 相机流前台服务 APK（v0.1.0，debug 签名，33 KB） |
| `screen-push.jar` | 屏幕流 dex.jar，`app_process` 用 `CLASSPATH` 直接加载（8 KB） |
| `FusionRecorder-Android-源码包.zip` | 完整源码 + 文档 + 构建脚本 |
| `DEPLOY.md` | 部署 / 启动 / 停止 / 排错命令 |
| `PROTOCOL.md` | H264 + SEI pts 线协议规格，含 iPhone 端 Swift 解析代码 |

> **仓库隔离**：本工程是独立目录 `/workspace/FusionRecorder-Android`，不写进
> `lathlee/FusionRecorder-iOS`，更不碰 `carnovel` / CarNovel / CarNotify 等其他应用。

---

## 3. 工程结构

```
FusionRecorder-Android/
├── build.py                     # 一键构建（含 pts 协议自测 + 产物校验）
├── README.md / DEPLOY.md / PROTOCOL.md
├── app/src/main/
│   ├── AndroidManifest.xml      # 纯 headless：无 LAUNCHER Activity
│   ├── java/com/fusion/capture/
│   │   ├── PtsSei.java            # ★ pts SEI NAL 构造/解析（APK 与 dex 共用）
│   │   ├── StreamSender.java      # ★ Annex-B → 插 SEI → 裸 TCP（两路共用）
│   │   ├── CameraStreamService.java  # 相机流：Camera2 → H264 → 9115
│   │   ├── KeepAliveActivity.java    # 全透明保活（§7 待办 3）
│   │   └── CaptureReceiver.java     # ADB 广播控制入口
│   └── res/                      # 透明主题、通知图标
├── screen/src/poc/ScreenPush.java   # 屏幕流：SurfaceControl 虚拟屏 → 9114
├── tools/
│   ├── SelfTest.java             # Java 端 pts 协议自测（565 条断言）
│   └── stream_probe.py          # 接收端参考实现 + 真机联调探针
└── dist/                        # 构建产物
```

`PtsSei.java` 和 `StreamSender.java` 被两条构建链**同时编译**，是 pts 编码与流输出的唯一真源——
两路不可能出现「格式各写一套」的情况。

---

## 4. 相对交接包 PoC 的改进

| 项 | 交接包 PoC | 本工程 |
|---|---|---|
| pts | ❌ 没有，成片按帧数均匀铺时间轴（§7 待办 1） | ✅ 每帧前插 SEI pts，两路共享单调时钟；**向后兼容旧接收端** |
| 静止画面 | ⚠️ VFR，屏幕静止几乎不产帧（曾掉到 1.4 fps） | ✅ `KEY_REPEAT_PREVIOUS_FRAME_AFTER` + 空闲心跳 SEI，接收端随时能抓到帧 |
| 时长 | 固定 10 秒 | ✅ `sec=0` 不限时，不受 `screenrecord` 180 s 限制；断开 TCP 即结束 |
| 参数 | 硬编码 640×400 / 640×480 | ✅ host / port / 分辨率 / 帧率 / 码率 / I 帧间隔 / 相机 id / pts 开关 全可配 |
| 保活 | 前台服务已不抢焦点，但未做透明化验证（§7 待办 3） | ✅ 全透明 Activity：不绘制、不拿焦点、触摸穿透，屏幕流抓的仍是真实 HUD |
| 停止 | 等时长到 | ✅ STOP 广播 / `force-stop` / 断开 TCP 三种方式 |
| 验证 | 人工看日志 | ✅ 构建期 595 条断言（Java 565 + Python 30）+ 跨语言字节级一致性校验 + 本机端到端打流 |

---

## 5. 构建

```bash
cd /workspace/FusionRecorder-Android
python3 build.py                 # 需要 JDK 17+ 与 Android SDK(build-tools 35 / platform 35)
```

构建链沿用交接包实测跑通的纯命令行方案，不需要 Gradle / Android Studio：

```
aapt2 compile → aapt2 link → javac --release 17 → d8 → 塞 classes.dex → zipalign → apksigner
                                    (APK)
javac → d8 → classes.dex 打进 zip   (screen-push.jar)
```

`build.py` 会先跑 `tools/SelfTest.java` 验证 pts 协议，任一条断言失败就中止构建；
构建完还会校验签名（v1+v2+v3）、`classes.dex` 魔数、manifest 里确实**没有** LAUNCHER Activity。

只重建不交付到 `/workspace` 根目录：`python3 build.py --no-deliver`。

---

## 6. 状态

| 项 | 状态 |
|---|---|
| 相机流服务、屏幕流 dex、透明保活、广播控制 | ✅ 已实现，本机编译通过 |
| pts SEI 封装 + 两路共享时钟 + 心跳 | ✅ 已实现，跨语言（Java/Python）字节级一致性自测通过 |
| 命令行构建链 + 静态校验 + 交付产物 | ✅ 已跑通；APK v1+v2+v3 签名、dex 039、无 LAUNCHER 均已校验 |
| 本机端到端打流（真实 TCP 两路、半包/粘包、NAL 切分、pts 解析、时钟对齐） | ✅ 已跑通，30 帧两路时间差恒为 8ms |
| **真机联调**（眼镜未接入本沙箱） | ⏳ 需按 `DEPLOY.md` 在真实眼镜上跑一遍 |
| iPhone 端 ADB RSA 授权（iOS `ADBClient`） | ⏳ 交接包 §7 待办 2，仍待真机联调 |
| iPhone 端解析 SEI pts 并按时间轴对齐 | ⏳ iOS v0.2；`PROTOCOL.md` 已给出可直接粘的 Swift 代码 |
| iPhone 端逐帧编码写 mp4 到相册 | ⏳ iOS v0.2；`MovieWriter` 目前仍是诚实标注的 TODO |

> 说明：本沙箱没有 Android 设备，MediaCodec / Camera2 / SurfaceControl 反射**无法在这里实跑**。
> 能在本机验证的（编译、签名、协议字节、NAL 切分、时钟对齐逻辑）都已验证；
> 需要真机的部分如实标注，没有把「编译通过」说成「真机跑通」。

---

## 7. 与 iPhone 端的关系

iPhone 端 v0.1.2 已经做到「预览 == 成片同源」（`FusionParams` + `FusionCompositor.render`）。
本采集端提供的就是那两路**真实帧源**：

- 点「从眼镜抓两帧」→ 连 9114 / 9115 各解一帧 → 合成真实预览图；
- 参数滑杆实时生效，且与最终写进成片的画面用同一个合成器；
- 采集端新增的 SEI pts 是 type 6 NAL，v0.1.2 的 `H264FrameDecoder` 走 `default` 分支直接忽略，
  **不升级 iPhone 端也不会坏**；v0.2 按 `PROTOCOL.md` 接上解析即可做逐帧时间对齐。
