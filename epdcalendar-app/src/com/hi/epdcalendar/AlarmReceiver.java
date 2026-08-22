package com.hi.epdcalendar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 到点的定时刷新：拉起刷新服务（服务完成后会自行布防下一轮） */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("EpdCal", "定时刷新闹钟触发");
        context.startService(new Intent(context, RefreshService.class));
    }
}
