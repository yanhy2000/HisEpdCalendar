"""把 classes.dex 追加进 aapt2 生成的 APK（本机无 zip 命令，用 Python zipfile 代替）"""
import sys
import zipfile


def main():
    apk, dex = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(apk, 'a') as z:
        names = z.namelist()
        if 'classes.dex' in names:
            raise SystemExit('APK 内已有 classes.dex，拒绝重复注入')
        z.write(dex, 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    print('classes.dex 已注入', apk)


if __name__ == '__main__':
    main()
