#!/usr/bin/env python3
"""把安卓采集端工程一次性提交到独立仓库 main 分支（走 REST Git Data API）。

为什么不用 git push：沙箱里 github.com:443 的 git smart HTTP 连不上，
api.github.com 通。安卓工程本地未初始化 git，所以这里直接遍历文件树，
按 .gitignore 规则排除生成物，不依赖 git 索引。

用法: GITHUB_TOKEN=... python3 tools/push_via_api.py <owner> <repo> [工程根目录]
"""

import base64
import fnmatch
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

TOKEN = os.environ.get("GITHUB_TOKEN", "")
if not TOKEN:
    sys.exit("缺少 GITHUB_TOKEN")

OWNER = sys.argv[1]
REPO = sys.argv[2]
ROOT = pathlib.Path(sys.argv[3]) if len(sys.argv) > 3 else pathlib.Path(".").resolve()
API = "https://api.github.com"
BASE = f"/repos/{OWNER}/{REPO}"


def req(method, path, payload=None, timeout=60):
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(
        API + path, data=data, method=method,
        headers={
            "Authorization": "Bearer " + TOKEN,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "FusionRecorder-Android-build",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
            return response.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        raw = error.read() or b"{}"
        try:
            return error.code, json.loads(raw)
        except Exception:
            return error.code, {"message": raw.decode(errors="replace")[:200]}


def load_ignore():
    rules = []
    ig = ROOT / ".gitignore"
    if ig.is_file():
        for line in ig.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                rules.append(line)
    return rules


def ignored(rel, rules):
    parts = rel.split("/")
    for rule in rules:
        r = rule.rstrip("/")
        if any(fnmatch.fnmatch(p, r) for p in parts):
            return True
        if fnmatch.fnmatch(rel, r):
            return True
    return False


def main():
    rules = load_ignore()
    files = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(ROOT).as_posix()
        if ignored(rel, rules):
            continue
        if rel.startswith(".git/"):
            continue
        files.append(rel)
    if not files:
        sys.exit("没有可推送的文件")
    print(f"待提交文件: {len(files)} 个")
    for rel in files:
        print("  ", rel)

    # 空仓库初始化：Git Data API 在完全空的仓库上 POST /git/blobs 会 409。
    code, ref = req("GET", f"{BASE}/git/ref/heads/main")
    if code != 200:
        seed = ROOT / "README.md"
        content = base64.b64encode(seed.read_bytes()).decode()
        code, result = req(
            "PUT", f"{BASE}/contents/README.md",
            {"message": "chore: 初始化独立仓库", "content": content},
        )
        if code not in (200, 201):
            sys.exit(f"初始化空仓库失败 HTTP {code}: {result}")
        print("空仓库已初始化（种子文件）")

    tree = []
    for index, rel in enumerate(files, 1):
        content = base64.b64encode((ROOT / rel).read_bytes()).decode()
        code, result = req("POST", f"{BASE}/git/blobs",
                           {"content": content, "encoding": "base64"})
        if code not in (200, 201) or "sha" not in result:
            sys.exit(f"blob 失败 [{rel}] HTTP {code}: {result}")
        mode = "100755" if rel.endswith(".sh") else "100644"
        tree.append({"path": rel, "mode": mode, "type": "blob", "sha": result["sha"]})
        if index % 5 == 0 or index == len(files):
            print(f"  blob {index}/{len(files)}")

    code, result = req("POST", f"{BASE}/git/trees", {"tree": tree})
    if code not in (200, 201):
        sys.exit(f"建 tree 失败 HTTP {code}: {result}")
    tree_sha = result["sha"]

    code, ref = req("GET", f"{BASE}/git/ref/heads/main")
    parents = [ref["object"]["sha"]] if code == 200 and "object" in ref else []
    code, result = req(
        "POST", f"{BASE}/git/commits",
        {"message": "feat: 安卓眼镜采集端 v0.1.0（相机流 APK + 屏幕流 dex + SEI pts + 一键部署）",
         "tree": tree_sha, "parents": parents},
    )
    if code not in (200, 201):
        sys.exit(f"建 commit 失败 HTTP {code}: {result}")
    commit_sha = result["sha"]

    if parents:
        code, result = req("PATCH", f"{BASE}/git/refs/heads/main",
                           {"sha": commit_sha, "force": True})
        action = "更新"
    else:
        code, result = req("POST", f"{BASE}/git/refs",
                           {"ref": "refs/heads/main", "sha": commit_sha})
        action = "创建"
    if code not in (200, 201):
        sys.exit(f"{action} main 失败 HTTP {code}: {result}")

    req("PATCH", BASE, {"default_branch": "main"})
    print(f"已{action} main: {commit_sha[:12]}（{len(files)} 个文件）")


if __name__ == "__main__":
    main()
