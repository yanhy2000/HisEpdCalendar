package com.hi.epdclock;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Xposed 模块入口（与控制台 App 合并为单 APK，本包仅含 hook 代码，
 * 不得引用 com.hi.epdcalendar 的类——这些类会被装载进 system_server 与锁屏进程）。
 *
 * 装载目标：
 *   com.hmct.einklockscreen（原厂墨水屏锁屏引擎，persistent，uid=system）
 *     · showMiniClock(Canvas)            → no-op（去原厂迷你钟）
 *     · drawWallpaper(Bitmap, Canvas)    → 我们的壁纸 + 实时时钟/电池
 *   android（system_server）
 *     · BottomBarController.getBottomBar → 原图直返（去解锁条/电池/白条）
 */
public class EpdClockModule implements IXposedHookLoadPackage {

    /** 开关标志（控制台 App 经 su 维护，hook 每次调用时检查，免重启切换）：
     *  .calendar_off 存在 → 还原原厂界面（所有 hook 回退原方法）
     *  .quiet         存在 → 刷白存放（保留去控件，但不画实时时钟/电池） */
    static final String FLAG_CALENDAR_OFF = "/sdcard/eink_clock/.calendar_off";
    static final String FLAG_QUIET = "/sdcard/eink_clock/.quiet";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        try {
            if ("com.hmct.einklockscreen".equals(lpparam.packageName)) {
                hookLockscreen(lpparam.classLoader);
            } else if ("android".equals(lpparam.packageName)) {
                hookSystemServer(lpparam.classLoader);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    static boolean flagExists(String path) {
        try {
            return new java.io.File(path).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean calendarOff() {
        return flagExists(FLAG_CALENDAR_OFF);
    }

    public static boolean quietMode() {
        return flagExists(FLAG_QUIET);
    }

    private static void hookLockscreen(ClassLoader cl) {
        try {
            Class<?> engine = XposedHelpers.findClass(
                    "com.hmct.einklockscreen.EInkLockScreenEngine", cl);
            XposedHelpers.findAndHookMethod(engine, "showMiniClock",
                    Canvas.class, new MiniClockHook());
            XposedHelpers.findAndHookMethod(engine, "drawWallpaper",
                    Bitmap.class, Canvas.class, new WallpaperHook());
            XposedBridge.log("EpdClockModule: lockscreen hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void hookSystemServer(ClassLoader cl) {
        try {
            Class<?> bar = XposedHelpers.findClass(
                    "com.android.server.BottomBarController", cl);
            XposedHelpers.findAndHookMethod(bar, "getBottomBar",
                    Bitmap.class, int.class, boolean.class, int.class,
                    new BottomBarHook());
            XposedBridge.log("EpdClockModule: system_server hook installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
