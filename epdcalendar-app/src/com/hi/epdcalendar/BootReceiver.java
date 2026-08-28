package com.hi.epdcalendar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 闹钟失联补救点（详见 Manifest intent-filter 注释）：
 * 开机/时间跳变/应用更新/解锁时重新布防（幂等）。自动刷新关闭时只清场不布防。
 * 注意：强停态下本接收器收不到任何广播，那条路由引擎侧看门狗兜底。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        DLog.i("补救广播 " + action + "：校准刷新闹钟");
        if (Config.autoRefresh(context)) {
            ScheduleLogic.armNext(context);
        } else {
            ScheduleLogic.cancelAlarm(context);
        }
        // 每次补救顺带自愈引擎设置（ROM 偶发清空导致画面冻结）；
        // su 执行不能在主线程广播里久留
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!Su.flagExists(".calendar_off")) {
                    Su.assertEngineSettings();
                }
                DLog.flush(context);
            }
        }).start();
    }
}
