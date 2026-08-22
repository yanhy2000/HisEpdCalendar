package com.hi.epdcalendar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 重启后闹钟会丢失，开机重新布防；并断言引擎设置（ROM 偶发清空导致画面冻结） */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("EpdCal", "开机自启：重新布防刷新闹钟");
        ScheduleLogic.armNext(context);
        // su 执行不能在主线程广播里久留
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!Su.flagExists(".calendar_off")) {
                    Su.assertEngineSettings();
                }
            }
        }).start();
    }
}
