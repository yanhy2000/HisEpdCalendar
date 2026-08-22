"""重建原版 XposedInstaller APK（撤销 v1 的字符串补丁，内容与镜像原版逐字节一致）

dex 的 sha1/adler32 头部字段是内容的确定函数，逆替换后重算即与原版完全一致。
随后剥离旧签名条目并由外部 jarsigner 重签（内容不变，签名换为我们自己的 key）。

用法：python tools/restore_installer.py
输入：XposedInstaller_315_sbinpatched.apk
输出：XposedInstaller_orig_clean.apk
"""
import hashlib
import struct
import zipfile
import zlib

SRC = 'XposedInstaller_315_sbinpatched.apk'
DST = 'XposedInstaller_orig_clean.apk'

BAD_FULL = b'\x11/sbin/xposed.prop' + b'\x00' * 6      # v1 补丁写入的 24 字节
ORIG = b'\x16/su/xposed/xposed.prop\x00'               # 原始 24 字节
assert len(BAD_FULL) == len(ORIG) == 24

zin = zipfile.ZipFile(SRC)
dex = zin.read('classes.dex')
assert struct.unpack_from('<I', dex, 32)[0] == len(dex), "输入 dex 头部不一致"
assert dex.count(BAD_FULL) == 1
dex = dex.replace(BAD_FULL, ORIG)

# 重算头部（与原版一致，因为内容已还原）
sha1 = hashlib.sha1(dex[32:]).digest()
dex = bytes(dex[:12] + sha1 + dex[32:])
adler = zlib.adler32(dex[12:]) & 0xFFFFFFFF
dex = dex[:8] + struct.pack('<I', adler) + dex[12:]
assert len(dex) == 2185228

with zipfile.ZipFile(DST, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        n = item.filename
        if n.startswith('META-INF/') and n.endswith(('.SF', '.RSA', '.DSA', '.EC', '.MF')):
            continue
        zout.writestr(item, dex if n == 'classes.dex' else zin.read(n))

out = zipfile.ZipFile(DST).read('classes.dex')
assert b'/su/xposed/xposed.prop' in out and b'/sbin' not in out
print(f"OK: {DST}（原版内容，待重签）")
