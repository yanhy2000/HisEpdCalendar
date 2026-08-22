"""XposedInstaller 3.1.5 检测路径补丁 · 最终版（排序安全）

替换：'/su/xposed/xposed.prop'(22字符) -> '/storage/emulated/0/xx'(22字符)
配套：prop 内容放 /sdcard/xx，并 pm grant 存储读取权限。

dex 约束逐条应对：
- 长度恒等：等长 22 字符，uleb128 前缀不变（v1 教训：禁止留死区空洞）
- 文件大小恒等：逐字节等长替换（v2 教训：模式串必须完整 24 字节）
- 字符串池有序：新串必须严格落在前驱/后继之间（v3 教训：/sbin 排序违规）
  本脚本先解析 string_ids 取出邻居并断言排序，不过则中止。
"""
import hashlib
import struct
import sys
import zipfile
import zlib

SRC = 'XposedInstaller_orig_clean.apk'      # 原版内容（未签名）
DST = 'XposedInstaller_v4_clean.apk'

OLD = b'\x16/su/xposed/xposed.prop\x00'     # 1+22+1 = 24
NEW_STR = b'/storage/emulated/0/xx'          # 8+9+2+3 = 22 字符
NEW = b'\x16' + NEW_STR + b'\x00'
assert len(NEW) == len(OLD) == 24

zin = zipfile.ZipFile(SRC)
dex = zin.read('classes.dex')
assert struct.unpack_from('<I', dex, 32)[0] == len(dex)

# ---- 解析 string_ids，找 OLD 的槽位与前驱/后继，验证排序 ----
def u4(off): return struct.unpack_from('<I', dex, off)[0]
def u2(off): return struct.unpack_from('<H', dex, off)[0]

string_ids_size, string_ids_off = u4(56), u4(60)

def get_string_bytes(idx):
    off = u4(string_ids_off + idx * 4)
    ln = dex[off]  # 全部 ASCII，<128
    return dex[off + 1:off + 1 + ln]

TARGET = b'/su/xposed/xposed.prop'
target = None
for i in range(string_ids_size):
    if get_string_bytes(i) == TARGET:
        target = i
        break
assert target is not None, 'string_ids 中找不到目标串'
prev_s = get_string_bytes(target - 1).decode('utf-8', 'replace')
next_s = get_string_bytes(target + 1).decode('utf-8', 'replace')
new_s = NEW_STR.decode()
assert prev_s.encode() < NEW_STR < next_s.encode(), \
    f'排序失败: {prev_s!r} < {new_s!r} < {next_s!r}'
print(f'排序验证通过: {prev_s!r} < {new_s!r} < {next_s!r}')

# ---- 替换 + 重算校验 ----
assert dex.count(OLD) == 1
dex = dex.replace(OLD, NEW)
sha1 = hashlib.sha1(dex[32:]).digest()
dex = bytes(dex[:12] + sha1 + dex[32:])
adler = zlib.adler32(dex[12:]) & 0xFFFFFFFF
dex = dex[:8] + struct.pack('<I', adler) + dex[12:]
assert struct.unpack_from('<I', dex, 32)[0] == len(dex)

with zipfile.ZipFile(DST, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        n = item.filename
        if n.startswith('META-INF/') and n.endswith(('.SF', '.RSA', '.DSA', '.EC', '.MF')):
            continue
        zout.writestr(item, dex if n == 'classes.dex' else zin.read(n))

out = zipfile.ZipFile(DST).read('classes.dex')
assert b'/storage/emulated/0/xx' in out and len(out) == len(dex)
print(f'OK: {DST}')
