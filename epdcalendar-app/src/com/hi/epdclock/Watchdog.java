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
 *    牵连不到）向 AlarmManager 布防 PendingIntent.getActivity(HealActivity)，
 *    目标 = 标记+20 分钟。v1.0.3 起用 setAlarmClock 用户级——真机实证
 *    本机 ROM 夜间深睡连 setExactAndAllowWhileIdle 都敢吞（连续两夜
 *    8:45 保险未开火、统计零记录），但厂商绝不敢吞用户级闹钟（否则
 *    用户的真闹钟不响）。
 * 2) 被动心跳兜底：心跳时发现标记已过期仍直接拉起（覆盖引擎闹钟也被
 *    清的极端情况，如重启后 BootReceiver 被拦）。
 *
 * 标记：App 布防闹钟时写入 /sdcard/eink_clock/.next_refresh（下次预期
 * 刷新时刻，root:sdcard_rw 660 与壁纸同属性）。缺失/0 = 无预期，不干预。
 * 追加式心跳日志 .wd_log（时刻 标记 保险闹钟目标，5 分钟一行，48KB 轮转）
 * ——下次事故可直接还原整夜时间线。
 */
final class Watchdog {
    private static final String MARKER = "/sdcard/eink_clock/.next_refresh";
    private static final String WDLOG = "/sdcard/eink_clock/.wd_log";
    private static final String HEAL_PKG = "com.hi.epdcalendar";
    private static final String HEAL_CLS = "com.hi.epdcalendar.HealActivity";
    private static final long CHECK_INTERVAL_MS = 5L * 60 * 1000;    // 真正检查的节流
    private static final long GRACE_MS = 20L * 60 * 1000;            // 主闹钟正常只晚数秒，宽限给足补刷时效
    private static final long LAUNCH_COOLDOWN_MS = 60L * 60 * 1000;  // 主动拉起冷却

    private static long sLastCheck;
    private static long sLastLaunch;
    private static boolean sBroken;        // 自愈目标已卸载，停用免空转
    private static PendingIntent sFailSafe;
    private static PendingIntent sShowPi;
    private static long sLastArmedAt = -1; // 上次保险闹钟目标（变更才记日志）

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
            long fs = maintainFailSafeAlarm(next);
            writeLog(now, next, fs);
            if (next <= 0 || now < next + GRACE_MS) {
                return;
            }
            launchHeal("被动心跳");
        } catch (Throwable t) {
            XposedBridge.log("EpdCal 看门狗：心跳异常: " + t);
        }
    }

    /**
     * 引擎身份布防/撤销保险闹钟：到期系统直接拉起 HealActivity（不依赖引擎运行）。
     * setAlarmClock 用户级：本机 ROM 夜间会吞 AllowWhileIdle 精确闹钟（实证），
     * 但绝不会吞用户级闹钟。返回当前布防时刻（未布防为 -1）。
     */
    private static long maintainFailSafeAlarm(long next) {
        try {
            android.content.Context ctx = appContext();
            if (ctx == null) {
                return sLastArmedAt;
            }
            AlarmManager am = (AlarmManager) ctx.getSystemService(android.content.Context.ALARM_SERVICE);
            if (sFailSafe == null) {
                android.content.Intent i = new android.content.Intent();
                i.setClassName(HEAL_PKG, HEAL_CLS);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                sFailSafe = PendingIntent.getActivity(ctx, 1002, i, PendingIntent.FLAG_UPDATE_CURRENT);
            }
            if (next > 0) {
                long at = next + GRACE_MS;
                if (sShowPi == null) {
                    // 状态栏闹钟图标的点击目标（主 LCD 才会显示，无实际影响）
                    android.content.Intent si = new android.content.Intent();
                    si.setClassName(HEAL_PKG, HEAL_PKG + ".MainActivity");
                    si.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    sShowPi = PendingIntent.getActivity(ctx, 1003, si, PendingIntent.FLAG_UPDATE_CURRENT);
                }
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, sShowPi), sFailSafe);
                if (at != sLastArmedAt) {
                    sLastArmedAt = at;
                    XposedBridge.log("EpdCal 看门狗：用户级保险闹钟布防 " + at);
                }
                return at;
            }
            am.cancel(sFailSafe);
            sLastArmedAt = -1;
            return -1;
        } catch (Throwable t) {
            XposedBridge.log("EpdCal 看门狗：保险闹钟布防失败: " + t);
            return sLastArmedAt;
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

    /** 追加式心跳日志：时刻 标记 保险闹钟目标（5 分钟一行；48KB 轮转；写失败静默） */
    private static void writeLog(long now, long marker, long fs) {
        FileOutputStream fos = null;
        try {
            File f = new File(WDLOG);
            if (f.length() > 48L * 1024) {
                f.delete();
            }
            fos = new FileOutputStream(f, true);
            fos.write((now + " " + marker + " " + fs + "\n").getBytes());
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
