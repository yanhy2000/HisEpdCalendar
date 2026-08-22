package com.hi.epdcalendar;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences 读写集中地 */
public final class Config {
    public static final String DEFAULT_PATTERN = "^(0|3|6|9|12|15|18|21):00$"; // 每 3 小时整点

    private Config() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public static String server(Context ctx) {
        return prefs(ctx).getString("server", "").trim();
    }

    public static String token(Context ctx) {
        return prefs(ctx).getString("token", "").trim();
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
