package de.robv.android.xposed;

/** XposedBridge API 编译桩（见 IXposedHookLoadPackage 说明） */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        param.setResult(replaceHookedMethod(param));
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
