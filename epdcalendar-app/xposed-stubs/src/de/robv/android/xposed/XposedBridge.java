package de.robv.android.xposed;

import java.lang.reflect.Member;

/** XposedBridge API 编译桩（见 IXposedHookLoadPackage 说明） */
public final class XposedBridge {

    private XposedBridge() {}

    public static void log(String text) {}

    public static void log(Throwable t) {}

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        throw new UnsupportedOperationException("stub");
    }
}
