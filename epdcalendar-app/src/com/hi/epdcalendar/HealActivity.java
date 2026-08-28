package com.hi.epdcalendar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 看门狗自愈入口：由锁屏引擎进程经 startActivity 拉起（Manifest 广播对强停态
 * App 会被系统直接丢弃，Activity 启动则可穿透并顺带清除 stopped 标记——
 * 这是 App 被系统强停/闹钟被清后唯一可靠的复活路径）。
 * 透明无界面：重新布防闹钟 + 触发一次刷新后立即退场，不出现在最近任务。
 */
public class HealActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DLog.i("自愈入口被拉起");
        try {
            if (Config.autoRefresh(this)) {
                long next = ScheduleLogic.armNext(this);
                if (next > 0) {
                    DLog.i("自愈：闹钟重新布防(" + next + ")并触发刷新");
                    startService(new Intent(this, RefreshService.class));
                } else {
                    DLog.w("自愈：布防失败(计划非法)，仅清理标记");
                    ScheduleLogic.cancelAlarm(this);
                    DLog.flushAsync(this);
                }
            } else {
                DLog.i("自愈：自动刷新已关闭，清理标记退出");
                ScheduleLogic.cancelAlarm(this);
                DLog.flushAsync(this);
            }
        } catch (Throwable t) {
            DLog.e("自愈失败: " + t);
            DLog.flushAsync(this);
        } finally {
            finish();
        }
    }
}
