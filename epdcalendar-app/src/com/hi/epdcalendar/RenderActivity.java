package com.hi.epdcalendar;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;

/**
 * 无感渲染宿主：锁屏之上、透明窗口内承载 WebView（真窗口才有真绘制表面，
 * 离屏 WebView 无论 view.draw 还是 capturePicture 都只能得到空白）。
 * 渲染完成立即 finish，短暂闪一下墨水屏属正常（本来每分钟也在刷）。
 *
 * 与 RefreshService 通过静态字段握手（同进程）：
 *   reset() → startActivity → await(latch) → 取 result/error
 */
public class RenderActivity extends Activity {
    static final int LAND_W = 1920, LAND_H = 1080;

    private static CountDownLatch sLatch;
    private static Bitmap sResult;
    private static String sError;

    private WebView web;
    private final Handler timeout = new Handler();
    private boolean finished = false;

    static void reset() {
        sLatch = new CountDownLatch(1);
        sResult = null;
        sError = null;
    }

    static Bitmap takeResult() {
        return sResult;
    }

    static String takeError() {
        return sError;
    }

    static CountDownLatch latch() {
        return sLatch;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        getWindow().setAttributes(lp);

        try {
            String json = readAll(new FileInputStream(
                    new File(getFilesDir(), RefreshService.RENDER_INPUT)));
            json = injectBattery(json);
            String html = RefreshService.buildHtml(this, json);

            web = new WebView(this);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(false);
            s.setAllowFileAccess(true); // 模板经 file:///android_asset/ 基址加载天气图标
            s.setUseWideViewPort(false);
            s.setLoadWithOverviewMode(false);
            // 像素精确配方：initialScale 直接定义 css px→设备 px 比例，绕开密度换算
            // （viewport meta 的 initial-scale 会与密度叠乘，在此设备上产生 320x180 的乱视口）
            web.setInitialScale(200); // 960x540 CSS → 1920x1080 物理
            web.setBackgroundColor(0); // 透明，减少墨水屏闪烁
            // 固定 1920x1080 像素：不随宿主窗口（当前活动屏）尺寸变化
            FrameLayout frame = new FrameLayout(this);
            frame.setBackgroundColor(0);
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(LAND_W, LAND_H);
            fp.gravity = Gravity.TOP | Gravity.START;
            frame.addView(web, fp);
            setContentView(frame);
            web.layout(0, 0, LAND_W, LAND_H); // 加载前定尺寸，Chromium 按此布局

            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    // 诊断：CSS 布局视口与 body 实际位置（排查内容偏移）+ 图标加载情况
                    view.evaluateJavascript(
                            "(function(){var b=document.body.getBoundingClientRect();"
                                    + "var wi=document.getElementById('wx-icon');"
                                    + "var ims=document.querySelectorAll('img');var a=[];"
                                    + "for(var i=0;i<ims.length;i++)a.push(ims[i].naturalWidth);"
                                    + "return 'iw='+window.innerWidth+' ih='+window.innerHeight"
                                    + "+' sy='+window.scrollY"
                                    + "+' bt='+b.top+' bl='+b.left+' bw='+b.width+' bh='+b.height"
                                    + "+' wxicon='+(wi?wi.naturalWidth:'-')+' imgs=['+a.join(',')+']';})()",
                            new android.webkit.ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    android.util.Log.i("EpdCal", "JS诊断: " + value);
                                    view.postDelayed(new Runnable() {
                                        @Override
                                        public void run() { capture(); }
                                    }, 600);
                                }
                            });
                }
            });
            web.loadDataWithBaseURL("file:///android_asset/", html,
                    "text/html; charset=utf-8", "utf-8", null);
            timeout.postDelayed(new Runnable() {
                @Override
                public void run() { done("WebView 渲染超时(25s)"); }
            }, 25000);
        } catch (Throwable t) {
            done("渲染宿主失败: " + t);
        }
    }

    private void capture() {
        try {
            Bitmap b = Bitmap.createBitmap(LAND_W, LAND_H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(android.graphics.Color.WHITE);
            web.layout(0, 0, LAND_W, LAND_H);
            web.draw(c);
            sResult = b;
            done(null);
        } catch (Throwable t) {
            done("截图失败: " + t);
        }
    }

    /** 把本机电池电量/充电状态注入 system_info.battery（模板左上角电量槽用） */
    private String injectBattery(String json) {
        try {
            android.content.Intent sticky = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int level = -1, scale = 100;
            boolean charging = false;
            if (sticky != null) {
                level = sticky.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                scale = sticky.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                int status = sticky.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            }
            int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;

            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONObject si = root.optJSONObject("system_info");
            if (si == null) {
                si = new org.json.JSONObject();
                root.put("system_info", si);
            }
            org.json.JSONObject b = new org.json.JSONObject();
            b.put("level", pct);
            b.put("charging", charging);
            si.put("battery", b);
            return root.toString();
        } catch (Throwable t) {
            android.util.Log.w("EpdCal", "电池注入失败: " + t);
            return json;
        }
    }

    private synchronized void done(String err) {
        if (finished) return;
        finished = true;
        if (err != null) sError = err;
        if (sLatch != null) sLatch.countDown();
        finish();
    }

    @Override
    protected void onDestroy() {
        timeout.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private static String readAll(FileInputStream in) throws Exception {
        InputStreamReader r = new InputStreamReader(in, "UTF-8");
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }
}
