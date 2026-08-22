package com.hi.epdcalendar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 控制台：配置服务端地址/密钥/刷新正则，测试解析，立即刷新，保存并布防。
 *
 * 尺寸约定：本机窗口度量永远来自 480dpi 主屏（1080 宽逻辑画布），墨水屏实际把
 * 该画布缩半显示（物理 540 宽）。因此以"540 基准 px"设计、乘 2 落到 1080 画布。
 * 不能用 sp——主屏密度 480dpi 会把 sp 再放大 3 倍，墨水屏上全部爆版。
 */
public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 1;

    private EditText etServer, etToken, etPattern;
    private TextView tvStatus, tvTest;
    private Switch swCalendar;
    private boolean suppressSwitch = true; // onResume 回填开关状态时不触发监听
    private final Handler handler = new Handler();
    private float S = 2f; // 显示缩放系数（540 基准 → 当前画布）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        S = Math.max(1.5f, dm.widthPixels / 360f); // 1080 画布 → 3.0（墨水屏缩半后 ≈ 1.5×基准）
        android.util.Log.i("EpdCal", "UI缩放 S=" + S + " (w=" + dm.widthPixels
                + " density=" + dm.density + ")");
        setContentView(buildUi());
        loadToFields();
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        suppressSwitch = true;
        swCalendar.setChecked(!Su.flagExists(".calendar_off"));
        suppressSwitch = false;
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    // ======================== UI ========================

    /** 540 基准 px → 实际 px */
    private int px(float v) {
        return Math.round(v * S);
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        tv.setTextColor(0xFF757575);
        tv.setPadding(0, px(12), 0, px(4));
        return tv;
    }

    private EditText field(String hint, boolean mono, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        et.setTextColor(0xFF212121);
        et.setHintTextColor(0xFF9E9E9E);
        et.setInputType(inputType);
        if (mono) et.setTypeface(Typeface.MONOSPACE);
        et.setPadding(px(8), px(8), px(8), px(8));
        return et;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFFAFAFA);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(16), px(14), px(16), px(20));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("墨水日历 · 控制台");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF212121);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(10.5f));
        tvStatus.setTextColor(0xFF2E7D32);
        tvStatus.setLineSpacing(px(2), 1f);
        tvStatus.setBackgroundColor(0xFFEEEEEE);
        tvStatus.setPadding(px(10), px(8), px(10), px(8));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, px(8), 0, 0);
        tvStatus.setLayoutParams(sp);
        root.addView(tvStatus);

        root.addView(label("服务端地址（含端口）"));
        etServer = field("http://你的服务器:5000", false,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(etServer);

        root.addView(label("API 密钥（X-API-Key）"));
        etToken = field("INKSYNC_API_KEY", false, InputType.TYPE_CLASS_TEXT);
        root.addView(etToken);

        root.addView(label("刷新时间正则（对每天 HH:mm 逐分钟匹配）"));
        etPattern = field(Config.DEFAULT_PATTERN, true, InputType.TYPE_CLASS_TEXT);
        root.addView(etPattern);

        TextView hint = new TextView(this);
        hint.setText("示例：^07:30$ 每天7点半 ｜ ^(0|3|6|9|12|15|18|21):00$ 每3小时整点\n"
                + "刷新时自动开 WiFi，结束后自动关（原本开着的不会关）");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(9.5f));
        hint.setTextColor(0xFF9E9E9E);
        hint.setLineSpacing(px(2), 1f);
        hint.setPadding(0, px(4), 0, 0);
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rp.setMargins(0, px(14), 0, 0);
        Button btnTest = new Button(this);
        btnTest.setText("测试解析");
        btnTest.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        btnTest.setLayoutParams(rp);
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { runParseTest(); }
        });
        Button btnNow = new Button(this);
        btnNow.setText("立即刷新");
        btnNow.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        btnNow.setLayoutParams(rp);
        ((LinearLayout.LayoutParams) btnNow.getLayoutParams()).leftMargin = px(10);
        btnNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { triggerRefresh(); }
        });
        row.addView(btnTest);
        row.addView(btnNow);
        root.addView(row);

        tvTest = new TextView(this);
        tvTest.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11.5f));
        tvTest.setTypeface(Typeface.MONOSPACE);
        tvTest.setTextColor(0xFF616161);
        tvTest.setLineSpacing(px(2), 1f);
        tvTest.setPadding(px(6), px(10), px(6), px(10));
        tvTest.setBackgroundColor(0xFFF1F1F1);
        tvTest.setMinLines(2);
        root.addView(tvTest);

        Button btnSave = new Button(this);
        btnSave.setText("保存并生效");
        btnSave.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { save(); }
        });
        root.addView(btnSave);

        // ===== 桌面日历模式开关 + 刷白存放 =====
        root.addView(label("桌面日历模式（关闭即还原原厂锁屏界面，下一分钟生效，无需重启）"));
        swCalendar = new Switch(this);
        swCalendar.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        swCalendar.setTextColor(0xFF212121);
        swCalendar.setPadding(0, px(2), 0, px(2));
        swCalendar.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                if (suppressSwitch) return;
                setCalendarMode(isChecked);
            }
        });
        root.addView(swCalendar);

        Button btnWhite = new Button(this);
        btnWhite.setText("刷白墨水屏（长期存放）");
        btnWhite.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        btnWhite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { whiteStorage(); }
        });
        root.addView(btnWhite);

        return scroll;
    }

    /** 日历模式开关：标志文件 + 壁纸索引 + 引擎设置自愈 + 闹钟；开启时立即刷一版内容 */
    private void setCalendarMode(boolean on) {
        boolean ok;
        if (on) {
            ok = Su.ok("rm -f /sdcard/eink_clock/.calendar_off /sdcard/eink_clock/.quiet")
                    && Su.ok("settings put secure eink_wallpaper_index -1")
                    && Su.assertEngineSettings();
            if (ok) {
                ScheduleLogic.armNext(this);
                toast("日历模式已开启，正在刷新内容…");
                startService(new Intent(this, RefreshService.class).putExtra("manual", true));
            } else {
                toast("开启失败（su 或权限异常）");
                revertSwitch();
            }
        } else {
            ok = Su.ok("touch /sdcard/eink_clock/.calendar_off")
                    && Su.ok("settings put secure eink_wallpaper_index 0");
            if (ok) {
                ScheduleLogic.cancelAlarm(this);
                toast("已还原原厂界面，下一分钟生效");
            } else {
                toast("关闭失败（su 或权限异常）");
                revertSwitch();
            }
        }
        refreshStatus();
    }

    private void revertSwitch() {
        suppressSwitch = true;
        swCalendar.setChecked(!Su.flagExists(".calendar_off"));
        suppressSwitch = false;
    }

    /** 刷白存放：纯白壁纸 + 静默模式（保留去控件但不画时钟），随后可关机长期收纳 */
    private void whiteStorage() {
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                    1080, 1920, android.graphics.Bitmap.Config.ARGB_8888);
            b.eraseColor(android.graphics.Color.WHITE);
            File t = new File(getFilesDir(), "white_stage.png");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(t);
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            b.recycle();

            String stage = "/sdcard/eink_clock/.white.su";
            String dst = "/sdcard/eink_clock/eink_lockscreen_wallpaper.png";
            boolean ok = Su.ok("cat '" + t.getAbsolutePath() + "' > '" + stage + "'"
                    + " && chown root:sdcard_rw '" + stage + "' && chmod 660 '" + stage + "'"
                    + " && mv -f '" + stage + "' '" + dst + "'"
                    + " && rm -f '" + t.getAbsolutePath() + "' /sdcard/eink_clock/.calendar_off"
                    + " && touch /sdcard/eink_clock/.quiet");
            ok = ok && Su.ok("settings put secure eink_wallpaper_index -1")
                    && Su.assertEngineSettings();
            if (ok) {
                ScheduleLogic.cancelAlarm(this);
                revertSwitch();
                toast("墨水屏将在下一分钟刷白，之后可关机存放");
            } else {
                toast("刷白失败（su 异常）");
            }
        } catch (Throwable tr) {
            toast("刷白失败: " + tr);
        }
    }

    // ======================== 行为 ========================

    private void loadToFields() {
        SharedPreferences p = Config.prefs(this);
        etServer.setText(p.getString("server", ""));
        etToken.setText(p.getString("token", ""));
        etPattern.setText(Config.pattern(this));
    }

    private void save() {
        String err = ScheduleLogic.validate(etPattern.getText().toString().trim());
        if (err != null) {
            toast("正则无效: " + err);
            return;
        }
        Config.prefs(this).edit()
                .putString("server", etServer.getText().toString().trim())
                .putString("token", etToken.getText().toString().trim())
                .putString("pattern", etPattern.getText().toString().trim())
                .apply();
        long next = ScheduleLogic.armNext(this);
        toast(next > 0 ? "已保存，下次刷新 " + fmt(next) : "已保存，但布防失败(" + next + ")");
        refreshStatus();
    }

    private void runParseTest() {
        String pattern = etPattern.getText().toString().trim();
        String err = ScheduleLogic.validate(pattern);
        if (err != null) {
            tvTest.setText("✗ 正则无效: " + err);
            tvTest.setTextColor(0xFFC62828);
            return;
        }
        List<Long> ms = ScheduleLogic.nextMatches(pattern, 5);
        if (ms.isEmpty()) {
            tvTest.setText("✗ 8 天内没有可匹配的时刻");
            tvTest.setTextColor(0xFFC62828);
            return;
        }
        tvTest.setTextColor(0xFF616161);
        SimpleDateFormat f = new SimpleDateFormat("MM-dd E HH:mm", Locale.CHINA);
        StringBuilder sb = new StringBuilder("接下来 ").append(ms.size()).append(" 次刷新:\n");
        for (long t : ms) sb.append("  ").append(f.format(new Date(t))).append('\n');
        tvTest.setText(sb.toString().trim());
    }

    private void triggerRefresh() {
        startService(new Intent(this, RefreshService.class).putExtra("manual", true));
        tvTest.setText("已触发刷新，取数+渲染+写入约需 20~60 秒…");
        tvTest.setTextColor(0xFF616161);
        for (int i = 1; i <= 12; i++) {
            final int delay = i * 5000;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { refreshStatus(); }
            }, delay);
        }
    }

    private void refreshStatus() {
        SharedPreferences p = Config.prefs(this);
        long next = p.getLong("next_at", -1);
        long last = p.getLong("last_at", 0);
        boolean ok = p.getBoolean("last_ok", false);
        String msg = p.getString("last_msg", "");
        StringBuilder sb = new StringBuilder();
        sb.append("下次刷新: ").append(next > 0 ? fmt(next) : "未布防");
        if (last > 0) {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
            sb.append("\n上次刷新: ").append(f.format(new Date(last))).append(' ')
              .append(ok ? "✓ " : "✗ ").append(msg);
        }
        tvStatus.setText(sb.toString());
        tvStatus.setTextColor(ok || last == 0 ? 0xFF2E7D32 : 0xFFC62828);
    }

    private String fmt(long t) {
        return new SimpleDateFormat("MM-dd E HH:mm", Locale.CHINA).format(new Date(t));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
