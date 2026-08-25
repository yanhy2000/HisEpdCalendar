package com.hi.epdcalendar;

import android.os.Environment;
import android.util.Log;

import java.io.File;

/** su 命令执行助手（Magisk 已授权本应用 uid；写 /sdcard 与改 secure settings 都需要 root） */
public final class Su {
    private static final String TAG = "EpdCal";

    private Su() {}

    /** 执行 su -c cmd，返回退出码；超时/异常返回 -1 */
    public static int run(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            long deadline = System.currentTimeMillis() + 15000;
            Integer rc = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    rc = p.waitFor();
                    break;
                } catch (InterruptedException e) {
                    Thread.sleep(200);
                }
            }
            if (rc == null) {
                p.destroy();
                Log.w(TAG, "su 超时: " + cmd);
                return -1;
            }
            return rc;
        } catch (Throwable t) {
            Log.w(TAG, "su 失败: " + cmd + " -> " + t);
            return -1;
        }
    }

    public static boolean ok(String cmd) {
        return run(cmd) == 0;
    }

    /**
     * 断言墨水屏锁屏时钟引擎的关键设置（ROM 偶发会把 lock_to_wallpaper /
     * back_lockscreen_show_time / ink_clock_duration 整体清空，导致引擎分钟闹钟
     * 照常触发却全部空转不上屏——画面冻结。刷新/开机/开关日历模式时调用自愈）。
     */
    public static boolean assertEngineSettings() {
        boolean ok = ok("settings put global lock_to_wallpaper 1"
                + " && settings put global back_lockscreen_show_time 1"
                + " && settings put system ink_clock_duration -1");
        // 顺带自愈电池优化白名单（保精确闹钟与 Doze 下联网不受限），尽力而为
        run("dumpsys deviceidle whitelist +com.hi.epdcalendar");
        return ok;
    }

    /** 开关标志文件是否存在（root 以 644 创建，普通 uid 可读） */
    public static boolean flagExists(String name) {
        return new File(Environment.getExternalStorageDirectory()
                + "/eink_clock/" + name).exists();
    }

    /**
     * 看门狗标记：布防闹钟时写入下次预期刷新时刻（epoch ms），取消/布防失败写 0。
     * 锁屏引擎进程里的看门狗据此判断闹钟链是否失联（详见 com.hi.epdclock.Watchdog）。
     * 与壁纸同属性（root:sdcard_rw 660）落位保证引擎可读。
     * 必须异步：armNext 会被主线程（onResume 等）调用，su 不能卡 UI。
     */
    public static void writeHealMarker(final long nextMs) {
        final String dir = "/sdcard/eink_clock";
        final String v = nextMs > 0 ? String.valueOf(nextMs) : "0";
        new Thread(new Runnable() {
            @Override
            public void run() {
                Su.run("echo -n " + v + " > " + dir + "/.next_refresh.tmp"
                        + " && chown root:sdcard_rw " + dir + "/.next_refresh.tmp"
                        + " && chmod 660 " + dir + "/.next_refresh.tmp"
                        + " && mv -f " + dir + "/.next_refresh.tmp " + dir + "/.next_refresh");
            }
        }, "EpdCal-marker").start();
    }
}
