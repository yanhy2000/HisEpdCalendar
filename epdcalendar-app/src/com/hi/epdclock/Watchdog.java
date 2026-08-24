package com.hi.epdclock;

import java.io.File;
import java.io.FileInputStream;

import de.robv.android.xposed.XposedBridge;

/**
 * 闹钟失联看门狗（运行在原厂锁屏引擎进程，随 drawWallpaper 每分钟心跳，
 * 引擎是 persistent 进程——事故中闹钟链全断时它照常每分钟在跑）。
 *
 * 机制：控制台 App 每次布防闹钟时把下次预期刷新时刻写入
 * /sdcard/eink_clock/.next_refresh（root:sdcard_rw 660，与壁纸同属性，
 * 本进程已在每分钟解码同目录壁纸，可读性已验证）。
 * 若预期时刻已过 45 分钟标记仍未更新（App 被系统强停清闹钟/广播被
 * autorun 拦截/时钟跳变），说明闹钟链已断——唯一可靠的拉起方式是
 * startActivity（可穿透强停态并顺带清除 stopped 标记；广播会被丢弃）。
 *
 * 标记缺失/为 0（未布防、模式关闭、布防失败）= 无预期，不干预。
 *
 * 注意：不能用 AndroidAppHelper 拿宿主 Context——本机 v89 框架的
 * XposedBridge.jar 不含该类（NoClassDefFoundError 实测），
 * 改走其底层实现 ActivityThread.currentApplication()。
 */
final class Watchdog {
    private static final String MARKER = "/sdcard/eink_clock/.next_refresh";
    private static final String HEAL_PKG = "com.hi.epdcalendar";
    private static final String HEAL_CLS = "com.hi.epdcalendar.HealActivity";
    private static final long CHECK_INTERVAL_MS = 5L * 60 * 1000;    // 真正检查的节流
    private static final long GRACE_MS = 45L * 60 * 1000;            // 预期时刻宽限
    private static final long LAUNCH_COOLDOWN_MS = 60L * 60 * 1000;  // 拉起冷却

    private static long sLastCheck;
    private static long sLastLaunch;
    private static boolean sBroken; // 拉起目标已卸载，停用免空转

    private Watchdog() {}

    /** 每分钟心跳入口（drawWallpaper 开头调用），自身吞掉一切异常 */
    static void tick() {
        if (sBroken) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sLastCheck < CHECK_INTERVAL_MS) {
            return;
        }
        sLastCheck = now;
        if (now - sLastLaunch < LAUNCH_COOLDOWN_MS) {
            return;
        }
        long next = readMarker();
        if (next <= 0 || now < next + GRACE_MS) {
            return;
        }
        android.content.Context ctx = appContext();
        if (ctx == null) {
            return; // 宿主 Application 还没就绪，下个检查窗口再试
        }
        sLastLaunch = now; // 先记冷却：即使失败也不能每分钟锤
        try {
            android.content.Intent i = new android.content.Intent();
            i.setClassName(HEAL_PKG, HEAL_CLS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            XposedBridge.log("EpdCal 看门狗：闹钟失联（预期 " + next + " 已过期 "
                    + ((now - next) / 60000L) + " 分钟），拉起自愈");
        } catch (Throwable t) {
            if (t instanceof android.content.ActivityNotFoundException) {
                sBroken = true;
                XposedBridge.log("EpdCal 看门狗：自愈目标不存在（App 已卸载？），停用: " + t);
            } else {
                XposedBridge.log("EpdCal 看门狗：拉起异常，按冷却重试: " + t);
            }
        }
    }

    /** 宿主进程的 Application（隐藏 API，AndroidAppHelper.currentApplication 的底层实现） */
    private static android.content.Context appContext() {
        try {
            return (android.content.Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long readMarker() {
        FileInputStream in = null;
        try {
            File f = new File(MARKER);
            if (!f.exists()) {
                return 0;
            }
            byte[] buf = new byte[32];
            in = new FileInputStream(f);
            int n = in.read(buf);
            return Long.parseLong(new String(buf, 0, Math.max(n, 0)).trim());
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
