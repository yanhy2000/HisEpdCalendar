package com.hi.epdclock;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/**
 * BottomBarController.getBottomBar(Bitmap, int, boolean, int)
 * → 原图直返（去解锁条/电池/白条；日历模式关闭时还原）
 */
public class BottomBarHook extends XC_MethodReplacement {

    @Override
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        if (EpdClockModule.calendarOff()) {
            return XposedBridge.invokeOriginalMethod(
                    param.method, param.thisObject, param.args);
        }
        return param.args[0];
    }
}
