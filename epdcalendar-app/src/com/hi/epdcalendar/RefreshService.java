package com.hi.epdcalendar;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

/**
 * 刷新流水线（工作线程执行，WebView 部分跳回主线程）：
 *   自愈引擎设置 → 网络策略（已联网直接用；否则按模式开 WiFi/流量，用完还原）
 *   → 本地数据组装（高德天气直连+缓存 / 一言 / lunar-java 农历）
 *   → WebView 渲染横版模板(960x540 CSS @2x → 1920x1080)
 *   → 顺时针旋转90° → 1080x1920 PNG 原子写入 /sdcard/eink_clock/
 *   → 还原网络现场 → 布防下一轮闹钟（自动刷新开启时）
 * 原厂锁屏引擎每分钟会重新读这张 PNG 上屏，无需主动通知。
 */
public class RefreshService extends Service {
    private static final String TAG = "EpdCal";
    static final String RENDER_INPUT = "render_input.json";
    private static final int LAND_W = 1920, LAND_H = 1080; // 横版渲染物理像素

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);

    private Handler main;
    private PowerManager.WakeLock wl;

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!sRunning.compareAndSet(false, true)) {
            DLog.w("已有刷新在进行，忽略本次触发");
            stopSelf();
            return START_NOT_STICKY;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EpdCal:refresh");
        wl.acquire(10 * 60 * 1000L);
        final boolean manual = intent != null && intent.getBooleanExtra("manual", false);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runPipeline(manual);
                } finally {
                    sRunning.set(false);
                }
            }
        }, "EpdCal-pipeline");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        return START_NOT_STICKY;
    }

    private void runPipeline(boolean manual) {
        boolean ok = false;
        String msg = "";
        NetPolicy.Session net = null;
        DLog.i(manual ? "手动刷新开始" : "定时刷新开始");
        try {
            // 自愈：ROM 偶发清空锁屏时钟设置会让引擎空转（画面冻结但闹钟仍在跳）
            Su.assertEngineSettings();

            // 1. 网络：已联网直接用（不动用户开关）；否则按模式按需开启（用完还原）
            net = NetPolicy.ensureNetwork(this);
            DLog.i("网络就绪");

            // 2. 本地数据组装（天气/一言需联网；农历全本地）
            long t0 = System.currentTimeMillis();
            JSONObject data = DataProvider.build(this);
            String json = data.toString();
            DLog.i("数据组装完成 " + json.length() + "B，耗时 "
                    + (System.currentTimeMillis() - t0) + "ms");
            writeInternal("last_data.json", json);

            // 3. 本地渲染（开机后立即自愈时 WebView 可能未就绪截出全白页，必须拦截）
            Bitmap portrait = renderChecked(json);

            // 4. 原子写入壁纸
            saveWallpaper(portrait);
            portrait.recycle();

            ok = true;
            msg = "成功";
        } catch (Throwable tr) {
            DLog.e("刷新失败: " + tr);
            msg = String.valueOf(tr);
        } finally {
            // 5. 还原网络现场、记录结果、布防下一轮
            try {
                NetPolicy.restore(this, net);
            } catch (Throwable tr) {
                DLog.w("网络还原失败: " + tr);
            }
            Config.recordResult(this, ok, msg);
            if (Config.autoRefresh(this)) {
                ScheduleLogic.armNext(this);
            } else if (!manual) {
                DLog.i("自动刷新已关闭，不布防下一轮");
            }
            DLog.i(ok ? "刷新完成：" + msg : "刷新失败：" + msg);
            DLog.flush(this);
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
            stopSelf();
        }
    }

    // ======================== 渲染（经 RenderActivity 宿主） ========================

    /**
     * 渲染 + 空白守卫：全白页（WebView 未就绪时可能截出，真机实证开机后
     * 约 15 分钟内高发）重试间隔递增 5/15/30/60 秒，仍空白则抛错——
     * 流水线判失败不落盘，保住屏幕上的旧画面而非白屏，闹钟链照常续排。
     */
    private Bitmap renderChecked(String json) throws Exception {
        int[] waits = {5000, 15000, 30000, 60000};
        for (int i = 0; i <= waits.length; i++) {
            Bitmap p = renderBitmap(json);
            if (!isBlank(p)) {
                return p;
            }
            DLog.w("渲染结果全白（WebView 未就绪？），丢弃重试 " + i + "/" + waits.length);
            p.recycle();
            if (i < waits.length) {
                Thread.sleep(waits[i]);
            }
        }
        throw new IllegalStateException("连续 " + (waits.length + 1) + " 次渲染全白");
    }

    /** 缩到 54x96 扫描深色像素，全白 = 空白；校验自身失败不拦截输出 */
    private static boolean isBlank(Bitmap b) {
        Bitmap t = null;
        try {
            t = Bitmap.createScaledBitmap(b, 54, 96, true);
            int dark = 0;
            for (int y = 0; y < 96 && dark == 0; y++) {
                for (int x = 0; x < 54; x++) {
                    int px = t.getPixel(x, y);
                    if ((android.graphics.Color.red(px) + android.graphics.Color.green(px)
                            + android.graphics.Color.blue(px)) / 3 < 128) {
                        dark++;
                        break;
                    }
                }
            }
            return dark == 0;
        } catch (Throwable tr) {
            DLog.w("空白校验异常，放行: " + tr);
            return false;
        } finally {
            if (t != null && t != b) {
                t.recycle();
            }
        }
    }

    /** 拉起渲染宿主 Activity 并等待结果，返回 1920x1080 横版位图 */
    private Bitmap renderBitmap(String json) throws Exception {
        writeInternal(RENDER_INPUT, json);
        RenderActivity.reset();
        Intent i = new Intent(this, RenderActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        if (!RenderActivity.latch().await(40, TimeUnit.SECONDS)) {
            throw new IllegalStateException("渲染宿主超时未返回");
        }
        String err = RenderActivity.takeError();
        if (err != null) {
            throw new IllegalStateException("渲染失败: " + err);
        }
        Bitmap land = RenderActivity.takeResult();
        if (land == null) {
            throw new IllegalStateException("渲染失败: 无输出");
        }

        Matrix m = new Matrix();
        m.postRotate(90); // 顺时针，与 PC 端 PIL ROTATE_270 等价 → 1080x1920 竖版
        Bitmap portrait = Bitmap.createBitmap(land, 0, 0, LAND_W, LAND_H, m, true);
        if (portrait != land) {
            land.recycle();
        }
        DLog.i("渲染完成 " + portrait.getWidth() + "x" + portrait.getHeight());
        return portrait;
    }

    /** 读 assets 模板，注入 INKSYNC_DATA 与天气图标 data-URI 表（缩放统一由
     *  RenderActivity 的 setInitialScale(200) 控制；loadDataWithBaseURL 的内容页
     *  不允许加载 file:// 子资源，SVG 图标必须内嵌） */
    static String buildHtml(android.content.Context ctx, String json) throws Exception {
        InputStream in = ctx.getAssets().open("template/landscape.html");
        String tpl = readAll(in);
        String inject = "<script>window.INKSYNC_DATA = " + json + ";</script>"
                + buildIconData(ctx, json);
        int head = tpl.indexOf("<head>");
        if (head < 0) {
            throw new IllegalStateException("模板缺少<head>");
        }
        return tpl.substring(0, head + 6) + inject + tpl.substring(head + 6);
    }

    /** 从 JSON 提取用到的天气图标代码，读 assets SVG 转 data-URI 映射表 */
    private static String buildIconData(android.content.Context ctx, String json) {
        try {
            java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
            org.json.JSONObject weather = new org.json.JSONObject(json).optJSONObject("weather");
            if (weather != null) {
                org.json.JSONObject live = weather.optJSONObject("live");
                if (live != null && live.has("icon")) {
                    codes.add(live.optString("icon"));
                }
                org.json.JSONArray fc = weather.optJSONArray("forecast");
                if (fc != null) {
                    for (int i = 0; i < fc.length(); i++) {
                        org.json.JSONObject d = fc.optJSONObject(i);
                        if (d != null && d.has("dayicon")) {
                            codes.add(d.optString("dayicon"));
                        }
                    }
                }
            }
            codes.add("999"); // 兜底图标
            StringBuilder sb = new StringBuilder("<script>window.ICON_DATA={");
            boolean first = true;
            for (String c : codes) {
                if (c == null || c.isEmpty()) {
                    continue;
                }
                try {
                    String svg = readAll(ctx.getAssets().open("template/icons/" + c + ".svg"));
                    String b64 = android.util.Base64.encodeToString(
                            svg.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('"').append(c)
                            .append("\":\"data:image/svg+xml;base64,").append(b64).append('"');
                    first = false;
                } catch (Throwable ignored) {
                } // 缺图跳过，模板 onerror 兜底
            }
            sb.append("};</script>");
            return sb.toString();
        } catch (Throwable t) {
            DLog.w("图标内嵌失败: " + t);
            return "";
        }
    }

    // ======================== 落盘 ========================

    private void saveWallpaper(Bitmap png) throws Exception {
        // 先写进应用私有目录（必然可写），再尝试直接落位；失败则走 su（本机 ROM 不给
        // 第三方应用 sdcard_rw 组，直写 /sdcard 会被 FUSE 拒绝，root 落位与 PC 端
        // pusher 的 su mv 模式一致，产出 root:sdcard_rw 660 的文件供锁屏引擎读取）
        File tmp = new File(getFilesDir(), "wallpaper_stage.png");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            png.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.getFD().sync();
        } finally {
            fos.close();
        }

        File dir = new File(android.os.Environment.getExternalStorageDirectory(), "eink_clock");
        File dst = new File(dir, "eink_lockscreen_wallpaper.png");

        if (tryDirectMove(tmp, dir, dst)) {
            DLog.i("壁纸直写成功 " + dst);
            return;
        }
        suMove(tmp, dir, dst);
        DLog.i("壁纸经 su 落位 " + dst + " (" + dst.length() + "B)");
    }

    /** 直接写入 /sdcard/eink_clock（在正常 ROM 上可行） */
    private boolean tryDirectMove(File tmp, File dir, File dst) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File stage = new File(dir, ".wallpaper.tmp");
            FileOutputStream fos = new FileOutputStream(stage);
            try {
                FileInputStream fin = new FileInputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                while ((n = fin.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fin.close();
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            if (dst.exists() && !dst.delete()) {
                return false;
            }
            if (!stage.renameTo(dst)) {
                return false;
            }
            tmp.delete();
            return true;
        } catch (Throwable tr) {
            DLog.w("直写失败，改用 su: " + tr);
            return false;
        }
    }

    /** su 落位：cat 到目标目录临时文件 → 属主改 root:sdcard_rw → 原子 mv 覆盖 */
    private void suMove(File tmp, File dir, File dst) throws Exception {
        String t = tmp.getAbsolutePath();
        String d = dst.getAbsolutePath();
        String stage = dir.getAbsolutePath() + "/.wallpaper.su";
        String cmd = "cat '" + t + "' > '" + stage + "' && chown root:sdcard_rw '" + stage
                + "' && chmod 660 '" + stage + "' && mv -f '" + stage + "' '" + d
                + "' && rm -f '" + t + "'";
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        Integer rc = null;
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            try {
                rc = p.waitFor();
                break;
            } catch (InterruptedException e) {
                Thread.sleep(200);
            }
        }
        if (rc == null) {
            p.destroy();
            throw new IllegalStateException("su 执行超时（首次使用请在 Magisk 中授权本应用）");
        }
        if (rc != 0) {
            throw new IllegalStateException("su 落位失败 rc=" + rc
                    + "（首次使用需在 Magisk 弹窗中授权）");
        }
        if (!dst.exists() || dst.length() == 0) {
            throw new IllegalStateException("su 落位后校验失败");
        }
    }

    private void writeInternal(String name, String content) {
        try {
            FileOutputStream fos = openFileOutput(name, MODE_PRIVATE);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable tr) {
            DLog.w("写 " + name + " 失败: " + tr);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.InputStreamReader r = new java.io.InputStreamReader(in, "UTF-8");
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        return sb.toString();
    }
}
