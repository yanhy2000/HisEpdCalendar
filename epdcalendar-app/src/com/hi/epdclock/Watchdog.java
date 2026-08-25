package com.hi.epdclock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import android.app.AlarmManager;
import android.app.PendingIntent;

import de.robv.android.xposed.XposedBridge;

/**
 * 闹钟失联看门狗（运行在原厂锁屏引擎进程，persistent、system uid）。
 *
 * v1.0.1 教训：被动心跳（drawWallpaper 分钟调用）在深睡下会随引擎绘制
 * 停摆而停跳——夜里恰是最需要看门狗的时候。v1.0.2 起双保险：
 *
 * 1) 主动保险闹钟：每次心跳用引擎身份（system uid，App 被强停清闹钟
 *    牵连不到；AllowWhileIdle 深睡照发）向 AlarmManager 布防一个
 *    PendingIntent.getActivity(HealActivity)，目标 = 标记+45 分钟。
 *    到期由系统直接拉起自愈——不需要引擎在跑、不走广播（会被丢弃）。
 * 2) 被动心跳兜底：心跳时发现标记已过期仍直接拉起（覆盖引擎闹钟也被
 *    清的极端情况，如重启后 BootReceiver 被拦）。
 *
 * 标记：App 布防闹钟时写入 /sdcard/eink_clock/.next_refresh（下次预期
 * 刷新时刻，root:sdcard_rw 660 与壁纸同属性）。缺失/0 = 无预期，不干预。
 * 另写 .wd_beat 心跳诊断文件（下次事故可判断守门员当时是否在跳）。
 */
final class Watchdog {
    private static final String MARKER = "/sdcard/eink_clock/.next_refresh";
    private static final String BEAT = "/sdcard/eink_clock/.wd_beat";
    private static final String HEAL_PKG = "com.hi.epdcalendar";
    private static final String HEAL_CLS = "com.hi.epdcalendar.HealActivity";
    private static final long CHECK_INTERVAL_MS = 5L * 60 * 1000;    // 真正检查的节流
    private static final long GRACE_MS = 45L * 60 * 1000;            // 预期时刻宽限
    private static final long LAUNCH_COOLDOWN_MS = 60L * 60 * 1000;  // 主动拉起冷却

    private static long sLastCheck;
    private static long sLastLaunch;
    private static boolean sBroken;        // 自愈目标已卸载，停用免空转
    private static PendingIntent sFailSafe;

    private Watchdog() {}

    /** 每分钟心跳入口（drawWallpaper / showMiniClock 开头调用），自身吞掉一切异常 */
    static void tick() {
        try {
            long now = System.currentTimeMillis();
            if (sBroken || now - sLastCheck < CHECK_INTERVAL_MS) {
                return;
            }
            sLastCheck = now;
            long next = readMarker();
            writeBeat(now);
            maintainFailSafeAlarm(next);
            if (next <= 0 || now < next + GRACE_MS) {
                return;
            }
            launchHeal("被动心跳");
        } catch (Throwable t) {
            XposedBridge.log("EpdCal 看门狗：心跳异常: " + t);
        }
    }

    /** 引擎身份布防/撤销保险闹钟：到期系统直接拉起 HealActivity（不依赖引擎运行） */
    private static void maintainFailSafeAlarm(long next) {
        android.content.Context ctx = appContext();
        if (ctx == null) {
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(android.content.Context.ALARM_SERVICE);
        if (sFailSafe == null) {
            android.content.Intent i = new android.content.Intent();
            i.setClassName(HEAL_PKG, HEAL_CLS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            sFailSafe = PendingIntent.getActivity(ctx, 1002, i, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        if (next > 0) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + GRACE_MS, sFailSafe);
        } else {
            am.cancel(sFailSafe);
        }
    }

    private static void launchHeal(String via) {
        android.content.Context ctx = appContext();
        if (ctx == null) {
            return; // 宿主 Application 还没就绪，下个检查窗口再试
        }
        long now = System.currentTimeMillis();
        if (now - sLastLaunch < LAUNCH_COOLDOWN_MS) {
            return;
        }
        sLastLaunch = now; // 先记冷却：即使失败也不能每分钟锤
        try {
            android.content.Intent i = new android.content.Intent();
            i.setClassName(HEAL_PKG, HEAL_CLS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            XposedBridge.log("EpdCal 看门狗：" + via + "发现闹钟失联，拉起自愈");
        } catch (Throwable t) {
            if (t instanceof android.content.ActivityNotFoundException) {
                sBroken = true;
                XposedBridge.log("EpdCal 看门狗：自愈目标不存在（App 已卸载？），停用: " + t);
            } else {
                XposedBridge.log("EpdCal 看门狗：拉起异常，按冷却重试: " + t);
            }
        }
    }

    /** 心跳诊断文件（诊断系统 uid 可写 FUSE；失败静默） */
    private static void writeBeat(long now) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(BEAT);
            fos.write(String.valueOf(now).getBytes());
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
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
