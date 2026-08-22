"""XposedInstaller 3.1.5 systemless 检测补丁（修订版）

历史教训：
- v1 补丁：改短字符串长度留下死区空洞 → ART 拒载（"String longer than indicated size 0"）
- v2 补丁：逆补丁模式串少写一个 \\x00，replace 后多出 1 字节 → "Bad file size 2185229"
- 本版：全部用等长斜杠填充路径（Unix 冗余分隔符合法），每步断言字节数

用法：python tools/patch_installer.py
输入：XposedInstaller_315_sbinpatched.apk（内含 v1 坏补丁的 24 字节模式）
输出：XposedInstaller_v3_clean.apk（待 jarsigner 签名）
"""
import hashlib
import struct
import zipfile
import zlib

SRC = 'XposedInstaller_315_sbinpatched.apk'
DST = 'XposedInstaller_v3_clean.apk'

BAD_FULL = b'\x11/sbin/xposed.prop' + b'\x00' * 6      # v1 实际写入 = 24 字节
ORIG = b'\x16/su/xposed/xposed.prop\x00'               # 原始 = 1+22+1 = 24 字节
P2_OLD = b'\x13/system/xposed.prop\x00'                # 1+19+1 = 21 字节
P2_NEW = b'\x13/sbin///xposed.prop\x00'                # 5+3+11=19 字符，等长 21 字节

assert len(BAD_FULL) == 24, len(BAD_FULL)
assert len(ORIG) == 24, len(ORIG)
assert len(P2_OLD) == 21 and len(P2_NEW) == 21, (len(P2_OLD), len(P2_NEW))

zin = zipfile.ZipFile(SRC)
dex = zin.read('classes.dex')
header_size = struct.unpack_from('<I', dex, 32)[0]
assert len(dex) == header_size, f"dex 长度 {len(dex)} != 头部 {header_size}"
print(f"输入 dex: {len(dex)} 字节（与头部一致）")

# 1) 还原 v1 坏补丁
assert dex.count(BAD_FULL) == 1, f"BAD_FULL 命中 {dex.count(BAD_FULL)} 次"
dex = dex.replace(BAD_FULL, ORIG)

# 2) 22 字符槽：/su/xposed/xposed.prop -> /sbin//////xposed.prop（6 斜杠填充）
p1_new = b'\x16' + b'/sbin//////xposed.prop' + b'\x00'
assert len(p1_new) == 24 and p1_new.count(b'/sbin') == 1
assert dex.count(ORIG) == 1
dex = dex.replace(ORIG, p1_new)

# 3) 19 字符槽：/system/xposed.prop -> /sbin///xposed.prop（3 斜杠填充）
assert dex.count(P2_OLD) == 1
dex = dex.replace(P2_OLD, P2_NEW)

assert len(dex) == header_size, f"补丁后长度 {len(dex)} != {header_size}"

# 4) 重算 dex 头校验：sha1(offset12..32 覆盖 32..尾) + adler32(8，覆盖 12..尾)
sha1 = hashlib.sha1(dex[32:]).digest()
dex = bytes(dex[:12] + sha1 + dex[32:])
adler = zlib.adler32(dex[12:]) & 0xFFFFFFFF
dex = dex[:8] + struct.pack('<I', adler) + dex[12:]
assert len(dex) == header_size

# 5) 重建 zip（剥离旧签名条目）
with zipfile.ZipFile(DST, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        n = item.filename
        if n.startswith('META-INF/') and n.endswith(('.SF', '.RSA', '.DSA', '.EC', '.MF')):
            continue
        data = dex if n == 'classes.dex' else zin.read(n)
        zout.writestr(item, data)

out_dex = zipfile.ZipFile(DST).read('classes.dex')
assert len(out_dex) == header_size
print(f"OK: {DST} classes.dex {len(out_dex)} 字节，等长替换无空洞")
