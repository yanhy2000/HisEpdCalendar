"""
在 PC 上预览手机端 App 的墨水屏模板（改模板先看效果，再部署手机）

用法:
    python tools/render_preview.py                     # 用 tmp/render_input.json（手机端真实云端数据）
    python tools/render_preview.py xxx.json            # 指定数据
    python tools/render_preview.py --battery 66        # 指定示例电量（默认 66，不充电）

渲染管线与手机一致：960x540 逻辑 @2x → 1920x1080 → 顺时针转90° → 1080x1920
输出: tmp/preview_land.png（横版）与 tmp/preview_portrait.png（手机视角竖版）
"""
import asyncio
import json
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
TPL = ROOT / 'epdcalendar-app' / 'assets' / 'template' / 'landscape.html'
OUT_DIR = ROOT / 'tmp'


async def shot(data: dict, out_png: Path):
    from playwright.async_api import async_playwright
    async with async_playwright() as p:
        try:
            browser = await p.chromium.launch(channel='msedge', headless=True)
        except Exception:
            browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(
            viewport={'width': 960, 'height': 540}, device_scale_factor=2)
        await page.add_init_script(
            f'window.INKSYNC_DATA = {json.dumps(data, ensure_ascii=False)}')
        await page.goto(TPL.as_uri(), wait_until='load')
        await page.wait_for_timeout(600)
        await page.screenshot(path=str(out_png))
        await browser.close()


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    battery = 66
    for a in sys.argv[1:]:
        if a.startswith('--battery'):
            battery = int(a.split('=')[1]) if '=' in a else int(sys.argv[sys.argv.index(a) + 1])

    src = Path(args[0]) if args else OUT_DIR / 'render_input.json'
    data = json.load(open(src, encoding='utf-8'))
    # 与手机端一致：注入示例电池（模板右上电量槽）
    data.setdefault('system_info', {})['battery'] = {'level': battery, 'charging': False}

    out_l = OUT_DIR / 'preview_land.png'
    OUT_DIR.mkdir(exist_ok=True)
    asyncio.run(shot(data, out_l))
    img = Image.open(out_l)
    portrait = img.transpose(Image.Transpose.ROTATE_270)
    out_p = OUT_DIR / 'preview_portrait.png'
    portrait.save(out_p)
    print(f'数据: {src}')
    print(f'横版: {out_l} {img.size}')
    print(f'竖版(手机视角): {out_p} {portrait.size}')


if __name__ == '__main__':
    main()
