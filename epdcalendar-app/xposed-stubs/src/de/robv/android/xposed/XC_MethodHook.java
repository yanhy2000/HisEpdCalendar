package de.robv.android.xposed;

import java.lang.reflect.Member;

/** XposedBridge API 编译桩（见 IXposedHookLoadPackage 说明） */
public class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public void setResult(Object result) {}
    }

    public class Unhook {
        public void unhook() {}
    }
}
