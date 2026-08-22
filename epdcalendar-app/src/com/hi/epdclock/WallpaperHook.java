package com.hi.epdclock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.io.FileInputStream;
import java.util.Calendar;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/**
 * drawWallpaper(Bitmap, Canvas) → 我们的横版壁纸 + 实时时钟/电池
 * （引擎每分钟唤醒绘制，时钟电池即分钟级实时，零额外唤醒）。
 *
 * 坐标均为竖屏画布：以 pivot(493,842) 顺时针 rotate(90) 绘制，
 * 横握（听筒朝左）时内容正立；横屏→竖屏换算 rx=lx-349, ry=ly+796。
 */
public class WallpaperHook extends XC_MethodReplacement {

    private static final String WALLPAPER = "/sdcard/eink_clock/eink_lockscreen_wallpaper.png";
    private static final int INK = 0xFF1A1A1A; // 与模板 --ink 一致

    @Override
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        // 日历模式关闭 → 完整回退原厂绘制
        if (EpdClockModule.calendarOff()) {
            return invokeOriginal(param);
        }
        try {
            Bitmap ours = BitmapFactory.decodeFile(WALLPAPER);
            if (ours == null) {
                return invokeOriginal(param);
            }
            Bitmap orig = (Bitmap) param.args[0];
            Canvas canvas = (Canvas) param.args[1];
            Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(ours, null,
                    new Rect(0, 0, orig.getWidth(), orig.getHeight()), smooth);

            // 刷白存放模式 → 只画壁纸不叠实时信息
            if (!EpdClockModule.quietMode()) {
                drawLiveTime(canvas);
                drawBattery(canvas);
            }
            return Boolean.TRUE;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return invokeOriginal(param);
        }
    }

    private static Object invokeOriginal(MethodHookParam param) throws Throwable {
        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
    }

    /**
     * 实时时钟：白槽(竖屏 483,800→525,950)擦除 + 34px 粗体时分，
     * 右对齐横屏 x=946（rotated-x=597），基线 ly=46 与月标题对齐。
     */
    static void drawLiveTime(Canvas canvas) {
        Calendar c = Calendar.getInstance();
        String text = pad(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad(c.get(Calendar.MINUTE));

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        canvas.drawRect(483f, 800f, 525f, 950f, p);

        p.setColor(INK);
        p.setTextSize(34f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float x = 597f - p.measureText(text);
        canvas.save();
        canvas.rotate(90f, 493f, 842f);
        canvas.drawText(text, x, 842f, p);
        canvas.restore();
    }

    /** 读 sysfs 小文件（电池 capacity/status，system uid 可读）；失败返回 null */
    private static String readSysFile(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 实时电池（右下角组）：百分比右对齐横屏 x=938（rx=589）基线 ry=1313；
     * 电池轮廓 858..892×502..518（STROKE 2.5）+ 电极头 892..898 + 电量条（宽 28*lvl/100）；
     * 充电时左侧闪电六边形。擦除区横屏 797..944 × 492..536 盖住模板静态槽。
     * 读取/解析失败整体跳过（模板静态槽兜底）。
     */
    static void drawBattery(Canvas canvas) {
        String cap = readSysFile("/sys/class/power_supply/battery/capacity");
        if (cap == null) {
            return;
        }
        int level;
        try {
            level = Integer.parseInt(cap.trim());
        } catch (Throwable t) {
            return;
        }
        if (level < 0) {
            level = 0;
        }
        if (level > 100) {
            level = 100;
        }

        boolean charging = false;
        String status = readSysFile("/sys/class/power_supply/battery/status");
        if (status != null) {
            status = status.trim();
            charging = status.contains("Charging") || "Full".equals(status);
        }

        canvas.save();
        canvas.rotate(90f, 493f, 842f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 白底擦除（盖住模板静态电池槽）
        p.setColor(0xFFFFFFFF);
        canvas.drawRect(448f, 1288f, 595f, 1332f, p);

        // "N%" 20px 粗体，右对齐 rx=594（横屏 940，擦除区右缘 595 内留 4px）；
        // 三位数（100%）过宽时把电池图标组（电极头/轮廓/电量条/闪电）整体左移，
        // 保持 12px 固定间距不重叠
        p.setColor(INK);
        p.setTextSize(20f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        String pct = level + "%";
        float textLeft = 594f - p.measureText(pct);
        float shift = Math.max(0f, 549f + 12f - textLeft);
        canvas.drawText(pct, textLeft, 1313f, p);

        // 电池轮廓 STROKE 2.5（横屏 858..892 × 502..518）
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        canvas.drawRect(509f - shift, 1298f, 543f - shift, 1314f, p);

        // 回 FILL：电极头（横屏 892..898 × 506..514）
        p.setStyle(Paint.Style.FILL);
        canvas.drawRect(543f - shift, 1302f, 549f - shift, 1310f, p);

        // 电量填充条（内区横屏 861..889 × 504..516，宽 28*lvl/100，整型运算与旧 smali 一致）
        float w = 28 * level / 100;
        canvas.drawRect(512f - shift, 1300f, 512f - shift + w, 1312f, p);

        // 充电：电池左侧闪电（横屏 837..849 × 500..520 的六边形折线）
        if (charging) {
            Path bolt = new Path();
            bolt.moveTo(496f - shift, 1296f);
            bolt.lineTo(488f - shift, 1307f);
            bolt.lineTo(493f - shift, 1307f);
            bolt.lineTo(490f - shift, 1316f);
            bolt.lineTo(500f - shift, 1304f);
            bolt.lineTo(495f - shift, 1304f);
            bolt.close();
            canvas.drawPath(bolt, p);
        }
        canvas.restore();
    }

    private static String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
