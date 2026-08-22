package de.robv.android.xposed.callbacks;

/** XposedBridge API 编译桩（见 de.robv.android.xposed.IXposedHookLoadPackage 说明） */
public class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}
