package com.hi.epdcalendar;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences 读写集中地（v1.0：本地数据源，无服务端） */
public final class Config {
    public static final String DEFAULT_PATTERN = "^(0|3|6|9|12|15|18|21):00$"; // 每 3 小时整点

    /** 网络模式 */
    public static final int NET_WIFI = 0; // 仅 WiFi
    public static final int NET_CELL = 1; // 仅流量
    public static final int NET_ANY = 2;  // 皆可（WiFi 优先，失败自动切流量）

    private Config() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public static String amapKey(Context ctx) {
        return prefs(ctx).getString("amap_key", "").trim();
    }

    /** 城市代码；空 = 刷新时按网络 IP 自动定位（高德 v3/ip，结果缓存 7 天） */
    public static String adcode(Context ctx) {
        return prefs(ctx).getString("adcode", "").trim();
    }

    public static int networkMode(Context ctx) {
        return prefs(ctx).getInt("network_mode", NET_WIFI);
    }

    public static boolean autoRefresh(Context ctx) {
        return prefs(ctx).getBoolean("auto_refresh", true);
    }

    /** 调试日志开关（刷新过程写入 /sdcard/eink_clock/debug.log；心跳 .wd_log 常开） */
    public static boolean debugLog(Context ctx) {
        return prefs(ctx).getBoolean("debug_log", false);
    }

    public static String pattern(Context ctx) {
        String p = prefs(ctx).getString("pattern", "").trim();
        return p.isEmpty() ? DEFAULT_PATTERN : p;
    }

    /** 记录一次刷新结果 */
    public static void recordResult(Context ctx, boolean ok, String msg) {
        prefs(ctx).edit()
                .putLong("last_at", System.currentTimeMillis())
                .putBoolean("last_ok", ok)
                .putString("last_msg", msg)
                .apply();
    }
}
