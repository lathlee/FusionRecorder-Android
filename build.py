#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
融合录屏 · 安卓眼镜采集端 —— 一键命令行构建（无需 Gradle / Android Studio）。

产出两个互相独立的部署单元：
  dist/fusion-capture.apk  相机流前台服务（App uid，需要 CAMERA 权限）
  dist/screen-push.jar      屏幕流 dex（shell uid，app_process 加载）

构建链沿用交接包里已实测跑通的纯命令行方案：
  APK : aapt2 compile → aapt2 link → javac → d8 → 塞 classes.dex → zipalign → apksigner
  DEX : javac → d8 → classes.dex 打进 zip

另外会编译执行 tools/SelfTest.java，验证 pts SEI 的
起始码 / NAL type / EBSP 无伪起始码 / pts+seq 往返 / 心跳标记 / 坏包拒绝。

用法：python3 build.py [--clean] [--sdk /opt/android-sdk] [--no-deliver]
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "app", "src", "main")
SCREEN = os.path.join(ROOT, "screen", "src")
TOOLS = os.path.join(ROOT, "tools")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")
WORKSPACE = os.path.dirname(ROOT)

MIN_SDK = "28"     # 目标设备 goolton ARGlasses = Android 9 / API 28
TARGET_SDK = "28"
RELEASE = "17"     # javac --release

KS = os.path.join(ROOT, "debug.keystore")
KS_PASS = "android"
KS_ALIAS = "androiddebugkey"


def log(msg):
    print("\n" + "=" * 68)
    print("  " + msg)
    print("=" * 68, flush=True)


def run(cmd, env=None, capture=False):
    print(">>> " + " ".join(str(c) for c in cmd[:8])
          + (" ..." if len(cmd) > 8 else ""), flush=True)
    r = subprocess.run([str(c) for c in cmd], env=env,
                        stdout=subprocess.PIPE if capture else None,
                        stderr=subprocess.STDOUT if capture else None,
                        text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        if capture:
            print((r.stdout or "")[-4000:])
        print("!!! FAILED rc=%d" % r.returncode)
        sys.exit(r.returncode)
    return (r.stdout or "").strip() if capture else ""


def tool_path(path):
    """Windows 下把无后缀工具名解析成真实的 .exe/.bat，Linux 原样返回。

    SDK build-tools 在 Windows 上给出的是 aapt2.exe / zipalign.exe / apksigner.bat，
    而脚本按 Linux 习惯写 "aapt2"。照抄原路径会在自检阶段直接失败。
    """
    if os.path.isfile(path):
        return path
    for ext in (".exe", ".bat", ".cmd"):
        candidate = path + ext
        if os.path.isfile(candidate):
            return candidate
    return path  # 保持原值，交给后面的自检给出明确报错


def find_sdk(explicit):
    candidates = [explicit,
                  os.environ.get("ANDROID_HOME"),
                  os.environ.get("ANDROID_SDK_ROOT"),
                  "/opt/android-sdk"]
    for c in candidates:
        if c and os.path.isfile(os.path.join(c, "platforms", "android-35", "android.jar")):
            return c
    sys.exit("找不到 Android SDK（需要 platforms;android-35 + build-tools;35.0.0），"
             "用 --sdk 指定或设置 ANDROID_HOME")


def find_jdk():
    javac = shutil.which("javac")
    home = os.environ.get("JAVA_HOME")
    if javac:
        candidate = os.path.dirname(os.path.dirname(os.path.realpath(javac)))
        bindir = os.path.join(candidate, "bin")
        if (os.path.isfile(os.path.join(bindir, "javac"))
                or os.path.isfile(os.path.join(bindir, "javac.exe"))):
            home = candidate
    if not home:
        sys.exit("找不到 javac（设置 JAVA_HOME 或把 JDK/bin 加进 PATH）")
    return home


class Env:
    def __init__(self, sdk, jdk):
        self.sdk = sdk
        self.jdk = jdk
        bt = os.path.join(sdk, "build-tools", "35.0.0")
        self.android_jar = os.path.join(sdk, "platforms", "android-35", "android.jar")
        self.aapt2 = tool_path(os.path.join(bt, "aapt2"))
        self.zipalign = tool_path(os.path.join(bt, "zipalign"))
        self.d8jar = os.path.join(bt, "lib", "d8.jar")
        self.javac = tool_path(os.path.join(jdk, "bin", "javac"))
        self.java = tool_path(os.path.join(jdk, "bin", "java"))
        self.keytool = tool_path(os.path.join(jdk, "bin", "keytool"))
        # Windows 的 build-tools 只提供 apksigner.bat，Python subprocess 不能直接启动 .bat。
        # 直接走 jar 里的 main class，Linux / Windows 行为一致。
        self.apksigner_jar = os.path.join(bt, "lib", "apksigner.jar")
        if os.path.isfile(self.apksigner_jar):
            self.apksigner = self.apksigner_jar
            self.apksigner_cmd = [self.java, "-cp", self.apksigner_jar,
                                  "com.android.apksigner.ApkSignerTool"]
        else:
            self.apksigner = tool_path(os.path.join(bt, "apksigner"))
            self.apksigner_cmd = [self.apksigner]
        self.env = dict(os.environ)
        self.env["JAVA_HOME"] = jdk
        self.env["ANDROID_HOME"] = sdk
        self.env["ANDROID_SDK_ROOT"] = sdk
        self.env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + self.env.get("PATH", "")
        for label, p in [("android.jar", self.android_jar), ("aapt2", self.aapt2),
                         ("zipalign", self.zipalign), ("apksigner", self.apksigner),
                         ("d8.jar", self.d8jar), ("javac", self.javac),
                         ("java", self.java), ("keytool", self.keytool)]:
            if not os.path.isfile(p):
                sys.exit("缺少构建工具 %s -> %s" % (label, p))


def prepare(e):
    for d in (BUILD, DIST):
        if os.path.isdir(d):
            shutil.rmtree(d)
    for sub in ("gen", "obj", "dex", "screen_obj", "screen_dex", "selftest"):
        os.makedirs(os.path.join(BUILD, sub), exist_ok=True)
    os.makedirs(DIST, exist_ok=True)

    if not os.path.isfile(KS):
        log("生成 debug.keystore（仅本地构建用，别当正式签名）")
        run([e.keytool, "-genkeypair", "-keystore", KS,
             "-storepass", KS_PASS, "-keypass", KS_PASS,
             "-alias", KS_ALIAS, "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-storetype", "PKCS12",
             "-dname", "CN=FusionCapture Debug,O=Fusion,C=CN"], env=e.env, capture=True)
    log("工具链自检")
    print(run([e.aapt2, "version"], env=e.env, capture=True))
    print(run([e.java, "-version"], env=e.env, capture=True).splitlines()[0])


def selftest(e):
    """纯 Java 验证 pts SEI 协议，不依赖 Android 运行时。"""
    log("[0/7] pts SEI 协议自测")
    srcs = [os.path.join(APP, "java", "com", "fusion", "capture", "PtsSei.java"),
            os.path.join(TOOLS, "SelfTest.java")]
    out = os.path.join(BUILD, "selftest")
    run([e.javac, "-encoding", "UTF-8", "--release", RELEASE, "-nowarn",
         "-d", out] + srcs, env=e.env)
    print(run([e.java, "-cp", out, "com.fusion.capture.SelfTest"], env=e.env, capture=True))


def build_apk(e):
    log("[1/7] aapt2 compile 资源")
    res_zip = os.path.join(BUILD, "res.zip")
    run([e.aapt2, "compile", "--dir", os.path.join(APP, "res"), "-o", res_zip], env=e.env)

    log("[2/7] aapt2 link（生成 R.java + 未签名 APK）")
    gen = os.path.join(BUILD, "gen")
    unsigned = os.path.join(BUILD, "unsigned.apk")
    run([e.aapt2, "link", res_zip, "-I", e.android_jar,
         "--java", gen, "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "-o", unsigned,
         "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
         "--auto-add-overlay"], env=e.env)

    log("[3/7] javac 编译 APK 侧 Java")
    obj = os.path.join(BUILD, "obj")
    srcs = glob.glob(os.path.join(APP, "java", "**", "*.java"), recursive=True)
    srcs += glob.glob(os.path.join(gen, "**", "*.java"), recursive=True)
    run([e.javac, "-encoding", "UTF-8", "--release", RELEASE, "-nowarn",
         "-classpath", e.android_jar, "-d", obj] + srcs, env=e.env)

    log("[4/7] d8 → classes.dex")
    dex = os.path.join(BUILD, "dex")
    classes = glob.glob(os.path.join(obj, "**", "*.class"), recursive=True)
    run([e.java, "-cp", e.d8jar, "com.android.tools.r8.D8",
         "--min-api", MIN_SDK, "--lib", e.android_jar, "--output", dex] + classes,
        env=e.env)

    log("[5/7] 打包 classes.dex + zipalign")
    packed = os.path.join(BUILD, "packed.apk")
    shutil.copy(unsigned, packed)
    with zipfile.ZipFile(packed, "a") as z:
        z.write(os.path.join(dex, "classes.dex"), "classes.dex")
    aligned = os.path.join(BUILD, "aligned.apk")
    run([e.zipalign, "-f", "4", packed, aligned], env=e.env)

    log("[6/7] apksigner 签名")
    apk = os.path.join(DIST, "fusion-capture.apk")
    # 同时启用 v1/v2/v3：定制 ROM 对 v3 的实现未必完整，三种都签最保险
    run(e.apksigner_cmd + ["sign", "--ks", KS,
         "--ks-pass", "pass:" + KS_PASS, "--key-pass", "pass:" + KS_PASS,
         "--ks-key-alias", KS_ALIAS, "--min-sdk-version", MIN_SDK,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true",
         "--out", apk, aligned], env=e.env)
    return apk


def build_screen_dex(e):
    log("[7/7] 构建 screen-push.jar（shell uid dex）")
    obj = os.path.join(BUILD, "screen_obj")
    dex = os.path.join(BUILD, "screen_dex")
    # 与 APK 共用 PtsSei / StreamSender 两份源码：pts 编码逻辑单一真源
    shared = os.path.join(APP, "java", "com", "fusion", "capture")
    srcs = glob.glob(os.path.join(SCREEN, "**", "*.java"), recursive=True)
    srcs += [os.path.join(shared, "PtsSei.java"),
             os.path.join(shared, "StreamSender.java")]
    run([e.javac, "-encoding", "UTF-8", "--release", RELEASE, "-nowarn",
         "-classpath", e.android_jar, "-d", obj] + srcs, env=e.env)
    classes = glob.glob(os.path.join(obj, "**", "*.class"), recursive=True)
    run([e.java, "-cp", e.d8jar, "com.android.tools.r8.D8",
         "--min-api", MIN_SDK, "--lib", e.android_jar, "--output", dex] + classes,
        env=e.env)
    jar = os.path.join(DIST, "screen-push.jar")
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(dex, "classes.dex"), "classes.dex")
    return jar


def verify(e, apk, jar):
    log("静态校验产物")
    # 用低 minSdk 强制把 v1/v2/v3 三种方案都验一遍，避免默认按 28 只报 v3 造成误读
    print(run(e.apksigner_cmd + ["verify", "--verbose", "--print-certs",
              "--min-sdk-version", "23", apk], env=e.env, capture=True)[:1500])
    badging = run([e.aapt2, "dump", "badging", apk], env=e.env, capture=True)
    for line in badging.splitlines():
        if line.startswith("package:") or "sdkVersion" in line or "launchable-activity" in line:
            print("  " + line)
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        assert "AndroidManifest.xml" in names, "manifest 缺失"
        assert "classes.dex" in names, "classes.dex 缺失"
        dex = z.read("classes.dex")
        assert dex[:4] == b"dex\n" and dex[4:7] in (b"035", b"037", b"038", b"039"), \
            "classes.dex 魔数不对: %r" % dex[:8]
        print("  APK: classes.dex %d 字节 (dex %s)，共 %d 项"
              % (len(dex), dex[4:7].decode(), len(names)))
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        dex = z.read("classes.dex")
        assert dex[:4] == b"dex\n" and dex[4:7] in (b"035", b"037", b"038", b"039"), \
            "screen dex 魔数不对: %r" % dex[:8]
        print("  JAR: classes.dex %d 字节，条目 %s" % (len(dex), names))
    # badging 里不应出现 launchable-activity（纯 headless 采集端）
    assert "launchable-activity" not in badging, "意外出现 LAUNCHER Activity"
    print("  确认：无 LAUNCHER Activity，屏幕流不会被采集 App 的界面挡住")


def deliver(apk, jar):
    log("交付到 /workspace 根目录")
    out = []
    for src in (apk, jar):
        dst = os.path.join(WORKSPACE, os.path.basename(src))
        shutil.copy(src, dst)
        out.append(dst)
        print("  %s  %d 字节" % (dst, os.path.getsize(dst)))
    # 一键安装脚本（Linux/macOS bash + Windows .bat）也放到同一目录
    for name in ("install_experiment.sh", "install_experiment.bat"):
        helper = os.path.join(ROOT, "tools", name)
        if os.path.isfile(helper):
            dst = os.path.join(WORKSPACE, name)
            shutil.copy(helper, dst)
            os.chmod(dst, 0o755)
            out.append(dst)
            print("  %s  %d 字节" % (dst, os.path.getsize(dst)))
    # 源码包（排除 build/dist 与二进制产物）
    srczip = os.path.join(WORKSPACE, "FusionRecorder-Android-源码包.zip")
    skip_dirs = {"build", "dist", "__pycache__"}
    with zipfile.ZipFile(srczip, "w", zipfile.ZIP_DEFLATED) as z:
        for base, dirs, files in os.walk(ROOT):
            dirs[:] = [d for d in dirs if d not in skip_dirs]
            for f in files:
                if f.endswith((".apk", ".jar", ".keystore", ".pyc")):
                    continue
                p = os.path.join(base, f)
                z.write(p, os.path.join("FusionRecorder-Android", os.path.relpath(p, ROOT)))
    out.append(srczip)
    print("  %s  %d 字节" % (srczip, os.path.getsize(srczip)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sdk", default=None)
    ap.add_argument("--no-deliver", action="store_true")
    args = ap.parse_args()

    sdk = find_sdk(args.sdk)
    jdk = find_jdk()
    print("SDK =", sdk)
    print("JDK =", jdk)
    e = Env(sdk, jdk)
    prepare(e)
    selftest(e)
    apk = build_apk(e)
    jar = build_screen_dex(e)
    verify(e, apk, jar)
    if not args.no_deliver:
        deliver(apk, jar)
    log("构建完成 ✅  fusion-capture.apk (%d B) + screen-push.jar (%d B)"
        % (os.path.getsize(apk), os.path.getsize(jar)))
    print("\n下一步见 DEPLOY.md：把 jar push 到眼镜、装 APK、授权 CAMERA、发两条启动命令。")


if __name__ == "__main__":
    main()
