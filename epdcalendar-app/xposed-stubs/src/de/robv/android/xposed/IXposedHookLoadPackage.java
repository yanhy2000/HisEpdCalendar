package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * XposedBridge API 编译桩：仅用于 javac/d8 编译期解析签名，
 * 方法体无意义、绝不打入 classes.dex；运行时由被 hook 进程内的
 * Xposed 框架（boot classloader）提供真实实现。
 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
