package com.hi.epdclock;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/** showMiniClock(Canvas) → no-op（去掉原厂迷你钟；日历模式关闭时还原） */
public class MiniClockHook extends XC_MethodReplacement {

    @Override
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        // 双心跳之一：壁纸绘制路径停摆（深睡）时迷你钟路径可能仍在跳
        Watchdog.tick();

        if (EpdClockModule.calendarOff()) {
            return XposedBridge.invokeOriginalMethod(
                    param.method, param.thisObject, param.args);
        }
        return null;
    }
}
