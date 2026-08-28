package com.hi.epdcalendar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 刷新计划：正则匹配 "HH:mm"（24 小时制，每分钟一个候选点）。
 * 从给定时刻之后的第一分钟起逐分钟向后扫描，找到第一个匹配的 HH:mm 即为下次刷新时间。
 * 同一时刻同时尝试 "08:00" 与 "8:00" 两种写法，^8:00$ 与 ^08:00$ 等效。
 *
 * 示例：
 *   ^7:30$ / ^07:30$              每天 07:30
 *   ^(0|3|6|9|12|15|18|21):00$    每逢 3 小时整点
 *   ^08:[03]0$                    08:00 与 08:30
 *   ^\d\d:00$                     每小时整点
 */
public final class ScheduleLogic {
    private static final String TAG = "EpdCal";
    private static final long SCAN_WINDOW_MS = 8L * 24 * 60 * 60 * 1000; // 最多向后扫 8 天

    private ScheduleLogic() {}

    /** 校验正则；非法返回错误信息，合法返回 null */
    public static String validate(String regex) {
        try {
            Pattern.compile(regex);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    /**
     * afterMs 之后第一个匹配的分钟时刻（epoch ms）。
     * 返回 -1 表示 8 天内无匹配；-2 表示正则非法。
     */
    public static long nextMatch(String regex, long afterMs) {
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return -2;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(afterMs);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MINUTE, 1);
        long limit = afterMs + SCAN_WINDOW_MS;
        while (c.getTimeInMillis() <= limit) {
            // 双形式匹配：真机事故实证 ^(8|13|17):00$ 因前导零永远匹配不上
            // "08:00"——用户以为设了早 8 点，实则四天"夜间失刷"全因此从未排过
            // 8 点档。同一分钟两种写法任一命中即算匹配。
            int h = c.get(Calendar.HOUR_OF_DAY);
            String mm = String.format("%02d", c.get(Calendar.MINUTE));
            if (p.matcher(String.format("%02d:%02d", h, c.get(Calendar.MINUTE))).matches()
                    || p.matcher(h + ":" + mm).matches()) {
                return c.getTimeInMillis();
            }
            c.add(Calendar.MINUTE, 1);
        }
        return -1;
    }

    /** 预览接下来 count 个匹配时刻（解析测试按钮用） */
    public static List<Long> nextMatches(String regex, int count) {
        List<Long> out = new ArrayList<>();
        long t = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            long m = nextMatch(regex, t);
            if (m < 0) break;
            out.add(m);
            t = m; // nextMatch 从 t 之后开始扫
        }
        return out;
    }

    /** 取消已布防的刷新闹钟（关闭日历模式时用） */
    public static void cancelAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, i, PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        Config.prefs(ctx).edit().putLong("next_at", -1L).apply();
        Su.writeHealMarker(0); // 无预期，看门狗停手
        DLog.i("刷新闹钟已取消");
    }

    /** 读取配置并布防下一次刷新闹钟；返回布防时刻，<0 表示失败 */
    public static long armNext(Context ctx) {
        String pattern = Config.pattern(ctx);
        long t = nextMatch(pattern, System.currentTimeMillis());
        if (t < 0) {
            DLog.e("armNext 失败: pattern=" + pattern + " code=" + t);
            Config.prefs(ctx).edit().putLong("next_at", -1L).apply();
            Su.writeHealMarker(0); // 布防失败清标记，避免看门狗每小时空拉起
            return t;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, i, PendingIntent.FLAG_UPDATE_CURRENT);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        Config.prefs(ctx).edit().putLong("next_at", t).apply();
        Su.writeHealMarker(t); // 引擎侧看门狗据此判断闹钟链是否失联
        DLog.i("已布防下次刷新: " + t + " pattern=" + pattern);
        return t;
    }
}
