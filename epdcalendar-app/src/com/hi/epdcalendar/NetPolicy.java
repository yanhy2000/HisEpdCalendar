package com.hi.epdcalendar;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;

/**
 * 刷新用网络策略：
 * - 已联网（WiFi 或流量）→ 直接使用现有网络，全程不动用户的网络开关
 * - 未联网 → 按模式按需打开 WiFi / 流量（su svc data），用完只关自己开过的
 * - 无 SIM 卡时流量相关模式自动退化为仅 WiFi
 */
public final class NetPolicy {

    private static final int WIFI_WAIT_MS = 45_000;
    private static final int CELL_WAIT_MS = 30_000;

    private NetPolicy() {}

    /** 本次刷新触碰过的网络开关（restore 只回滚这些，绝不碰用户自己开着的） */
    public static class Session {
        boolean wifiWeEnabled;
        boolean dataWeEnabled;
    }

    public static boolean hasSim(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getSimState() != TelephonyManager.SIM_STATE_ABSENT;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean connected(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    private static boolean waitConnected(Context ctx, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (connected(ctx)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 确保刷新期间有网络。成功返回会话（restore 用）；失败抛异常（已自动还原现场）。
     */
    public static Session ensureNetwork(Context ctx) throws IllegalStateException {
        // 已联网：直接用，什么都不动
        if (connected(ctx)) {
            return new Session();
        }

        int mode = Config.networkMode(ctx);
        if (!hasSim(ctx) && mode != Config.NET_WIFI) {
            mode = Config.NET_WIFI; // 无 SIM：流量无意义，退化为仅 WiFi
        }

        Session s = new Session();
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if (mode != Config.NET_CELL) {
            if (wm != null && !wm.isWifiEnabled()) {
                try {
                    wm.setWifiEnabled(true);
                    s.wifiWeEnabled = true;
                } catch (Throwable ignored) {
                }
            }
            if (waitConnected(ctx, WIFI_WAIT_MS)) {
                return s;
            }
            // WiFi 失败：仅 WiFi 模式到此为止；皆可模式收掉 WiFi 再试流量
            if (s.wifiWeEnabled && wm != null) {
                try {
                    wm.setWifiEnabled(false);
                } catch (Throwable ignored) {
                }
                s.wifiWeEnabled = false;
            }
            if (mode == Config.NET_WIFI) {
                throw new IllegalStateException("联网失败：WiFi 未在 "
                        + (WIFI_WAIT_MS / 1000) + "s 内连上（仅 WiFi 模式）");
            }
        }

        // 流量通道（需要 root：第三方 app 无权直接开关移动数据）
        // 注：TelephonyManager.getDataEnabled 为隐藏 API，用 Settings.Global.mobile_data 判断
        boolean dataWasOn = false;
        try {
            dataWasOn = android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "mobile_data", 0) == 1;
        } catch (Throwable ignored) {
        }
        if (!dataWasOn) {
            if (!Su.ok("svc data enable")) {
                throw new IllegalStateException("联网失败：开启移动数据需要 root（Magisk 授权）");
            }
            s.dataWeEnabled = true;
        }
        if (waitConnected(ctx, CELL_WAIT_MS)) {
            return s;
        }
        restore(ctx, s);
        throw new IllegalStateException("联网失败：WiFi 与流量均未连上");
    }

    /** 还原现场：只关本会话开启过的开关 */
    public static void restore(Context ctx, Session s) {
        if (s == null) {
            return;
        }
        if (s.dataWeEnabled) {
            Su.ok("svc data disable");
        }
        if (s.wifiWeEnabled) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wm.setWifiEnabled(false);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
