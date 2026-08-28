"""
构建/部署合并版墨水日历 App（控制台 + Xposed 模块同体，包名 com.hi.epdcalendar）

链路：javac(Xposed API 桩) → stubs.jar → javac(App + hook 源码) → D8
      → aapt2 link → 注入 classes.dex → jarsigner 签名 → 可选安装/部署
（Xposed API 桩仅作编译期 classpath，绝不打入 dex；运行时由框架提供真实实现）

用法：
    python tools/build.py              # 仅构建 → epdcalendar-app/build/epdcalendar-signed.apk
    python tools/build.py --install    # 构建 + 安装 + 补权限/Doze 白名单
    python tools/build.py --deploy     # install + modules.list 同步 + 卸载旧模块 + 重启验证
    python tools/build.py --deploy --no-reboot   # 部署但不重启
    python tools/build.py --deploy --keep-old    # 保留旧 com.hi.epdclock 模块不卸载

依赖：JDK 8+（javac/jar/jarsigner/java，PATH 没有时自动探测常见安装位置）
      + adb + python3
      + tools/android-sdk/{android-25.jar, r8.jar}（下载源见 README「新电脑重建开发环境」）
      + tools/apktool-bin/aapt2_64.exe（从 apktool.jar 内 prebuilt/windows/ 抽取）
      + xposed-sign.keystore（丢失则无法覆盖升级！）
"""
import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "epdcalendar-app"
OUT = APP / "build"
AJAR = ROOT / "tools" / "android-sdk" / "android-25.jar"
R8 = ROOT / "tools" / "android-sdk" / "r8.jar"
AAPT2 = ROOT / "tools" / "apktool-bin" / "aapt2_64.exe"
KS = Path(os.environ.get("EPD_KEYSTORE") or ROOT / "xposed-sign.keystore")
KS_PASS = os.environ.get("EPD_KEYSTORE_PASS", "")
STUBS_SRC = APP / "xposed-stubs" / "src"

PKG = "com.hi.epdcalendar"          # 合并后唯一包名（沿用 App 包名原地升级，保 uid/配置/su 授权）
OLD_MODULE_PKG = "com.hi.epdclock"  # 旧独立模块 APK（合并后卸载；hook 类包名不受影响）
MODULES_LIST = "/data/user_de/0/de.robv.android.xposed.installer/conf/modules.list"
XPOSED_LOG = "/data/user_de/0/de.robv.android.xposed.installer/log/error.log"


def run(cmd, quiet=False, check=True):
    cmd = [str(c) for c in cmd]
    if not quiet:
        print("+", " ".join(cmd))
    # errors=replace：jarsigner/adb 在中文 Windows 上输出 GBK，按 UTF-8 解会炸读线程
    r = subprocess.run(cmd, capture_output=quiet, text=True,
                       encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        if quiet:
            print(r.stdout or "", r.stderr or "")
        sys.exit(f"命令失败({r.returncode}): {' '.join(cmd)}")
    return r


def adb_shell(cmd, check=True):
    return run(["adb", "shell", cmd], quiet=True, check=check)


def find_jdk():
    """javac 不在 PATH 时，探测常见 JDK 安装位置（winget 安装后旧会话 PATH 未刷新）"""
    if shutil.which("javac"):
        return
    import glob as _glob
    globs = [
        r"C:\Program Files\Eclipse Adoptium\jdk-*\bin",
        r"C:\Program Files\Microsoft\jdk-*\bin",
        r"C:\Program Files\Java\jdk-*\bin",
        r"C:\Program Files\Android\Android Studio\jbr\bin",
    ]
    for g in globs:
        hits = [Path(p) for p in _glob.glob(g)]
        if hits:
            os.environ["PATH"] = str(hits[-1]) + os.pathsep + os.environ["PATH"]
            print(f"已探测 JDK: {hits[-1]}")
            return
    sys.exit("PATH 缺少 javac（需要 JDK 8+），也未在常见位置找到 JDK 安装")


def check_deps():
    missing = [str(p) for p in (AJAR, R8, AAPT2) if not p.exists()]
    if missing:
        sys.exit("缺少构建依赖:\n  " + "\n  ".join(missing)
                 + "\n获取方法见 docs/开发指南.md")
    if not KS.exists():
        sys.exit("缺少签名密钥。首次使用请自建（严禁入库），可用环境变量 EPD_KEYSTORE 指定路径:\n"
                 "  keytool -genkeypair -keystore xposed-sign.keystore -alias xposed"
                 " -keyalg RSA -keysize 2048 -validity 10000")
    if not KS_PASS:
        sys.exit("请通过环境变量 EPD_KEYSTORE_PASS 提供密钥密码")
    find_jdk()
    for tool in ("javac", "jar", "jarsigner", "adb"):
        if shutil.which(tool) is None:
            sys.exit(f"PATH 缺少 {tool}")


def build():
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "obj").mkdir(parents=True)
    (OUT / "stubs-obj").mkdir(parents=True)
    (OUT / "dex").mkdir(parents=True)

    print("[1/7] javac 编译 Xposed API 编译桩（仅编译期，不进 dex）")
    run(["javac", "--release", "8", "-encoding", "UTF-8", "-nowarn",
         "-cp", AJAR,
         "-d", OUT / "stubs-obj",
         *[str(p) for p in STUBS_SRC.rglob("*.java")]])
    run(["jar", "cf", OUT / "xposed-api-stubs.jar", "-C", OUT / "stubs-obj", "."],
        quiet=True)

    print("[2/7] javac 编译主源码（控制台 App + Xposed hook）")
    argfile = OUT / "sources.txt"
    # javac argfile 引号内反斜杠是转义符，路径必须用正斜杠
    argfile.write_text(
        "\n".join(f'"{p.as_posix()}"' for p in (APP / "src").rglob("*.java")),
        encoding="utf-8")
    run(["javac", "--release", "8", "-encoding", "UTF-8", "-nowarn",
         "-cp", f"{AJAR};{OUT / 'xposed-api-stubs.jar'}",
         "-d", OUT / "obj", f"@{argfile}"])

    print("[3/7] D8 转 dex（桩仅作 classpath，不会打进产物）")
    run(["java", "-cp", R8, "com.android.tools.r8.D8", "--release",
         "--lib", AJAR, "--classpath", OUT / "xposed-api-stubs.jar",
         "--output", OUT / "dex",
         *[str(p) for p in (OUT / "obj").rglob("*.class")]])

    print("[4/7] aapt2 打包资源/清单/assets（含 xposed_init 与渲染模板）")
    run([AAPT2, "link", "-o", OUT / "unsigned.apk", "-I", AJAR,
         "--manifest", APP / "AndroidManifest.xml", "-A", APP / "assets",
         "--min-sdk-version", "25", "--target-sdk-version", "25",
         # 版本策略：versionName 只随特性版本推进（1.0 → 1.1…），纯 bug 修复
         # 保持不变（同号覆盖安装可行）；versionCode 单调不减即可，避免跨
         # 版本线部署时触发 INSTALL_FAILED_VERSION_DOWNGRADE。
         # v1.0.1（code 105）= 1.0 之后全部修复的合并发行版
         "--version-code", "105", "--version-name", "1.0.1"])

    print("[5/7] 注入 classes.dex")
    run([sys.executable, ROOT / "tools" / "add_dex.py",
         OUT / "unsigned.apk", OUT / "dex" / "classes.dex"])

    print("[6/7] jarsigner 签名")
    run(["jarsigner", "-keystore", KS, "-storepass", KS_PASS,
         "-signedjar", OUT / "epdcalendar-signed.apk", OUT / "unsigned.apk",
         "xposed"], quiet=True)

    apk = OUT / "epdcalendar-signed.apk"
    print(f"[7/7] 完成: {apk} ({apk.stat().st_size} B)")


def install():
    run(["adb", "install", "-r", "-d", OUT / "epdcalendar-signed.apk"])  # -d 允许降级（换版本线时）
    # 本机 ROM 的 install -r 会清运行时权限，装完必须补授
    adb_shell(f"pm grant {PKG} android.permission.WRITE_EXTERNAL_STORAGE")
    adb_shell(f"dumpsys deviceidle whitelist +{PKG}")


def deploy(reboot=True, keep_old=False):
    install()

    # 本机 ROM 的 install -r 会更换应用目录（-1 → -2 …）且旧目录延迟清理，
    # modules.list 必须同步为 pm path 实际值并恢复 SELinux 标签，否则下次开机模块失效
    r = adb_shell(f"pm path {PKG}")
    modpath = r.stdout.strip().splitlines()[0].removeprefix("package:").strip()
    print(f"modules.list -> {modpath}")
    # modules.list 写入（2026-08-22 实证：读取者为 xposed_zygote_service，untrusted_app 域，
    # 只要 conf 目录保留 App 自建的类别标签，文件本身 app_data/system_file 标签均可读）。
    # 写完把属主/标签还原为安装器 App 的原生形态，保证其 UI 开关可持续写入
    # （root 属主会挡住 App 的开关写操作，且卸载时清不干净）
    r = adb_shell("dumpsys package de.robv.android.xposed.installer | grep userId")
    uid = ""
    for part in (r.stdout or "").split():
        if part.startswith("userId="):
            uid = part.split("=")[1]
    label = f"chown {uid}:{uid} {MODULES_LIST}" if uid else ":"
    adb_shell(f"su -c 'echo {modpath} > {MODULES_LIST} && {label} "
              f"&& chcon u:object_r:app_data_file:s0:c512,c768 {MODULES_LIST}'")

    if not keep_old:
        print(f"卸载旧独立模块 {OLD_MODULE_PKG}（hook 类已并入本 APK）")
        r = run(["adb", "uninstall", OLD_MODULE_PKG], quiet=True, check=False)
        if r.returncode != 0:
            print(f"  （旧模块可能已卸载: {(r.stdout or r.stderr or '').strip()}）")

    if not reboot:
        print("已按 --no-reboot 跳过重启；重启后模块 hook 生效")
        return

    print("重启（模块类在 zygote 装载，重启后生效）...")
    run(["adb", "reboot"], quiet=True)
    for _ in range(60):
        time.sleep(10)
        s = adb_shell("getprop sys.boot_completed", check=False)
        if s.returncode == 0 and s.stdout.strip() == "1":
            break
    print("开机完成，验证模块加载（应见 EpdClockModule: ... hooks installed）：")
    adb_shell(f"su -c 'grep -i epdclock {XPOSED_LOG} | tail -3'")


def main():
    ap = argparse.ArgumentParser(description="合并版墨水日历 App 构建/部署")
    ap.add_argument("--install", action="store_true", help="构建后安装并补权限/白名单")
    ap.add_argument("--deploy", action="store_true",
                    help="install + modules.list 同步 + 卸载旧模块 + 重启验证")
    ap.add_argument("--no-reboot", action="store_true", help="配合 --deploy 跳过重启")
    ap.add_argument("--keep-old", action="store_true",
                    help="不卸载旧 com.hi.epdclock 独立模块")
    args = ap.parse_args()

    check_deps()
    build()
    if args.deploy:
        deploy(reboot=not args.no_reboot, keep_old=args.keep_old)
    elif args.install:
        install()


if __name__ == "__main__":
    main()
