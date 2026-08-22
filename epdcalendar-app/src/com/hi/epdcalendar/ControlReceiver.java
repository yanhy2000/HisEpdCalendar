package com.hi.epdcalendar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * adb 调试入口（也可被其它本机程序调用）：
 *   am broadcast -a com.hi.epdcalendar.ACTION_CONFIG --es amap_key XXX --es adcode 330100 --es pattern "^07:30$"
 *   am broadcast -a com.hi.epdcalendar.ACTION_REFRESH
 */
public class ControlReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("com.hi.epdcalendar.ACTION_CONFIG".equals(action)) {
            SharedPreferences.Editor e = Config.prefs(context).edit();
            String amapKey = intent.getStringExtra("amap_key");
            String adcode = intent.getStringExtra("adcode");
            String pattern = intent.getStringExtra("pattern");
            if (amapKey != null) {
                e.putString("amap_key", amapKey.trim());
            }
            if (adcode != null) {
                e.putString("adcode", adcode.trim());
            }
            if (pattern != null) {
                e.putString("pattern", pattern.trim());
            }
            e.apply();
            long next = ScheduleLogic.armNext(context);
            Log.i("EpdCal", "远程配置已保存 amap_key="
                    + (amapKey == null ? "-" : "***") + " adcode=" + adcode
                    + " pattern=" + pattern + " next=" + next);
        } else if ("com.hi.epdcalendar.ACTION_REFRESH".equals(action)) {
            Log.i("EpdCal", "远程触发立即刷新");
            context.startService(new Intent(context, RefreshService.class).putExtra("manual", true));
        }
    }
}
