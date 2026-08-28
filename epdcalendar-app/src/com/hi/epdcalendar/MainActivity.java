package com.hi.epdcalendar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 控制台（v1.0 本地数据源版）：分区卡片布局，即改即存。
 *
 * 尺寸约定：本机窗口度量永远来自 480dpi 主屏（1080 宽逻辑画布），墨水屏实际把
 * 该画布缩半显示（物理 540 宽）。因此以"540 基准 px"设计、乘 2 落到 1080 画布。
 * 不能用 sp——主屏密度 480dpi 会把 sp 再放大 3 倍，墨水屏上全部爆版。
 */
public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 1;

    private EditText etKey, etAdcode, etPattern;
    private TextView tvStatus, tvTest;
    private Switch swCalendar, swAuto;
    private RadioGroup rgNet;
    private boolean suppress = true; // 程序化回填控件时不触发保存
    private final Handler handler = new Handler();
    private float S = 2f; // 显示缩放系数（540 基准 → 当前画布）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        S = Math.max(1.5f, dm.widthPixels / 360f);
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
        refreshStatus();
        suppress = true;
        swCalendar.setChecked(!Su.flagExists(".calendar_off"));
        swAuto.setChecked(Config.autoRefresh(this));
        checkNetModeAvailable();
        suppress = false;
        // HMCT 会拦截开机广播导致重启后闹钟丢失，打开 App 即自愈布防
        if (Config.autoRefresh(this) && !Su.flagExists(".calendar_off")) {
            ScheduleLogic.armNext(this);
            refreshStatus();
        }
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

    /** 白底圆角卡片 */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(px(10));
        c.setBackground(bg);
        c.setPadding(px(16), px(14), px(16), px(16));
        return c;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(0xFF455A64);
        return tv;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        tv.setTextColor(0xFF90A4AE);
        tv.setPadding(0, px(10), 0, px(3));
        return tv;
    }

    private EditText field(String hint, boolean mono, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        et.setTextColor(0xFF263238);
        et.setHintTextColor(0xFFB0BEC5);
        et.setInputType(inputType);
        if (mono) {
            et.setTypeface(Typeface.MONOSPACE);
        }
        et.setPadding(px(8), px(6), px(8), px(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFAFBFC);
        g.setCornerRadius(px(7));
        g.setStroke(Math.max(1, px(0.75f)), 0xFFCFD8DC);
        et.setBackground(g);
        return et;
    }

    /** 主按钮（深底白字）/ 次按钮（描边） */
    private Button button(String text, final boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        b.setTextColor(primary ? 0xFFFFFFFF : 0xFF546E7A);
        b.setAllCaps(false);
        b.setPadding(px(14), 0, px(14), 0);
        b.setMinimumHeight(px(34));
        b.setHeight(px(34));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? 0xFF37474F : 0x00000000);
        g.setCornerRadius(px(7));
        if (!primary) {
            g.setStroke(Math.max(1, px(0.75f)), 0xFF90A4AE);
        }
        b.setBackground(g);
        return b;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(10));
        tv.setTextColor(0xFFB0BEC5);
        tv.setPadding(0, px(5), 0, 0);
        return tv;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF0F1F3);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(14), px(14), px(14), px(22));
        scroll.addView(root);

        // ---- 标题 ----
        TextView title = new TextView(this);
        title.setText("墨水日历");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(21));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF263238);
        root.addView(title);
        TextView subtitle = hint("本地数据源 · 无需服务端 · v1.0.1");
        subtitle.setPadding(0, 0, 0, px(10));
        root.addView(subtitle);

        // ======== 卡片1：数据源 ========
        LinearLayout c1 = card();
        c1.addView(sectionTitle("数据源"));

        c1.addView(label("高德天气 Key（获取方法点右侧 ? ）"));
        LinearLayout rowKey = new LinearLayout(this);
        rowKey.setOrientation(LinearLayout.HORIZONTAL);
        rowKey.setGravity(Gravity.CENTER_VERTICAL);
        etKey = field("粘贴你的高德 Key", true, android.text.InputType.TYPE_CLASS_TEXT);
        rowKey.addView(etKey, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button btnHelp = button("?", false);
        LinearLayout.LayoutParams lpH = new LinearLayout.LayoutParams(
                px(34), px(34), 0f);
        lpH.leftMargin = px(8);
        btnHelp.setLayoutParams(lpH);
        rowKey.addView(btnHelp);
        c1.addView(rowKey);
        etKey.addTextChangedListener(new SimpleText() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!suppress) {
                    Config.prefs(MainActivity.this).edit()
                            .putString("amap_key", s.toString().trim()).apply();
                }
            }
        });
        btnHelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyHelp();
            }
        });

        c1.addView(label("城市代码 adcode（留空 = 自动定位）"));
        etAdcode = field("留空按网络 IP 自动定位", true, android.text.InputType.TYPE_CLASS_NUMBER);
        c1.addView(etAdcode);
        etAdcode.addTextChangedListener(new SimpleText() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!suppress) {
                    String v = s.toString().trim();
                    if (v.isEmpty() || v.matches("\\d{4,6}")) {
                        Config.prefs(MainActivity.this).edit().putString("adcode", v).apply();
                    }
                }
            }
        });
        c1.addView(hint("天气需要高德 Key（? 查看获取方法）；城市自动按 IP 定位，"
                + "定位不准可手动填 adcode；农历/黄历/一言开箱即用"));
        root.addView(c1, cardParams());

        // ======== 卡片2：刷新计划 ========
        LinearLayout c2 = card();
        c2.addView(sectionTitle("刷新计划"));

        c2.addView(label("刷新正则（对 HH:mm 逐分钟匹配，8:00 与 08:00 等效）"));
        LinearLayout rowPat = new LinearLayout(this);
        rowPat.setOrientation(LinearLayout.HORIZONTAL);
        rowPat.setGravity(Gravity.CENTER_VERTICAL);
        etPattern = field(Config.DEFAULT_PATTERN, true,
                android.text.InputType.TYPE_CLASS_TEXT);
        rowPat.addView(etPattern, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button btnTest = button("测试", false);
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, px(34), 0f);
        lpT.leftMargin = px(8);
        btnTest.setLayoutParams(lpT);
        rowPat.addView(btnTest);
        c2.addView(rowPat);
        etPattern.addTextChangedListener(new SimpleText() {
            @Override
            public void afterTextChanged(Editable s) {
                String p = s.toString().trim();
                if (!suppress && ScheduleLogic.validate(p) == null) {
                    Config.prefs(MainActivity.this).edit().putString("pattern", p).apply();
                }
            }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runParseTest();
            }
        });

        tvTest = new TextView(this);
        tvTest.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        tvTest.setTextColor(0xFF607D8B);
        tvTest.setPadding(0, px(6), 0, 0);
        c2.addView(tvTest);

        Button btnRefresh = button("立即刷新", true);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(38));
        lpR.topMargin = px(10);
        btnRefresh.setLayoutParams(lpR);
        c2.addView(btnRefresh);
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerRefresh();
            }
        });

        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        tvStatus.setTextColor(0xFF607D8B);
        tvStatus.setPadding(0, px(8), 0, 0);
        c2.addView(tvStatus);
        root.addView(c2, cardParams());

        // ======== 卡片3：网络 ========
        LinearLayout c3 = card();
        c3.addView(sectionTitle("网络"));
        c3.addView(label("刷新时使用哪种网络"));
        rgNet = new RadioGroup(this);
        rgNet.setOrientation(LinearLayout.HORIZONTAL);
        String[] netNames = {"仅 WiFi", "仅流量", "皆可"};
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(netNames[i]);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12));
            rb.setTextColor(0xFF37474F);
            rb.setId(100 + i);
            rgNet.addView(rb);
        }
        c3.addView(rgNet);
        rgNet.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (!suppress) {
                    Config.prefs(MainActivity.this).edit()
                            .putInt("network_mode", checkedId - 100).apply();
                }
            }
        });
        c3.addView(hint("已联网（WiFi/流量）时直接使用现有网络，不会动你的开关；\n"
                + "未联网时才按上述选择临时开启，刷新完自动关闭（流量需 root）"));
        root.addView(c3, cardParams());

        // ======== 卡片4：模式 ========
        LinearLayout c4 = card();
        c4.addView(sectionTitle("模式"));

        swCalendar = new Switch(this);
        swCalendar.setText("桌面日历");
        swCalendar.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        swCalendar.setTextColor(0xFF263238);
        c4.addView(swCalendar);
        swCalendar.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (!suppress) {
                    setCalendarMode(on);
                }
            }
        });

        swAuto = new Switch(this);
        swAuto.setText("自动刷新（按刷新计划定时）");
        swAuto.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        swAuto.setTextColor(0xFF263238);
        swAuto.setPadding(0, px(8), 0, 0);
        c4.addView(swAuto);
        swAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (suppress) {
                    return;
                }
                Config.prefs(MainActivity.this).edit().putBoolean("auto_refresh", on).apply();
                if (on) {
                    long next = ScheduleLogic.armNext(MainActivity.this);
                    toast(next > 0 ? "自动刷新已开启，下次 " + fmt(next)
                            : "开启失败：正则 8 天内无可匹配时刻");
                } else {
                    ScheduleLogic.cancelAlarm(MainActivity.this);
                    toast("自动刷新已关闭（仍可手动立即刷新）");
                }
            }
        });

        Button btnWhite = button("刷白墨水屏（长期收纳）", false);
        LinearLayout.LayoutParams lpW = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(34));
        lpW.topMargin = px(12);
        btnWhite.setLayoutParams(lpW);
        c4.addView(btnWhite);
        btnWhite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmWhiteStorage();
            }
        });
        root.addView(c4, cardParams());

        TextView brand = new TextView(this);
        brand.setText("HisEpdCalendar · github.com/yanhy2000/HisEpdCalendar");
        brand.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(9));
        brand.setTextColor(0xFFB0BEC5);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, px(12), 0, 0);
        root.addView(brand);

        return scroll;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        return lp;
    }

    /** 回填网络模式；无 SIM 时禁用流量相关选项并回退仅 WiFi */
    private void checkNetModeAvailable() {
        boolean sim = NetPolicy.hasSim(this);
        RadioButton cell = (RadioButton) findViewById(101);
        RadioButton any = (RadioButton) findViewById(102);
        if (cell != null) {
            cell.setEnabled(sim);
        }
        if (any != null) {
            any.setEnabled(sim);
        }
        int mode = Config.networkMode(this);
        if (!sim && mode != Config.NET_WIFI) {
            mode = Config.NET_WIFI;
            Config.prefs(this).edit().putInt("network_mode", mode).apply();
        }
        rgNet.check(100 + mode);
    }

    // ======================== 行为 ====================

    private void showKeyHelp() {
        new AlertDialog.Builder(this)
                .setTitle("如何获取高德天气 Key")
                .setMessage("1、浏览器打开 console.amap.com/dev/key/app 并登录高德账号\n\n"
                        + "2、页面右上角点击「创建新应用」，名称随意，类型选「其他」\n\n"
                        + "3、创建完成后在应用卡片右上角点击「添加 Key」，名称随意，"
                        + "服务平台选「Web」，提交即可获得 Key\n\n"
                        + "4、复制 Key 粘贴到本页输入框即可。城市会按网络 IP 自动定位，"
                        + "定位不准时再手动填写所在城市的 adcode\n\n"
                        + "个人开发者免费额度对本应用的用量（每几小时 2 次请求）绰绰有余")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void setCalendarMode(boolean on) {
        boolean ok;
        if (on) {
            ok = Su.ok("rm -f /sdcard/eink_clock/.calendar_off /sdcard/eink_clock/.quiet")
                    && Su.ok("settings put secure eink_wallpaper_index -1")
                    && Su.assertEngineSettings();
            if (ok) {
                if (Config.autoRefresh(this)) {
                    ScheduleLogic.armNext(this);
                }
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
        suppress = true;
        swCalendar.setChecked(!Su.flagExists(".calendar_off"));
        suppress = false;
    }

    private void confirmWhiteStorage() {
        new AlertDialog.Builder(this)
                .setTitle("刷白墨水屏")
                .setMessage("将写入纯白壁纸并进入静默模式（保留去控件但不画时钟），"
                        + "下一分钟整屏刷白后可关机长期收纳。\n\n确定执行？")
                .setPositiveButton("刷白", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        whiteStorage();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 刷白存放：纯白壁纸 + 静默模式（保留去控件但不画时钟），随后可关机长期收纳 */
    private void whiteStorage() {
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                    1080, 1920, android.graphics.Bitmap.Config.ARGB_8888);
            b.eraseColor(android.graphics.Color.WHITE);
            java.io.File t = new java.io.File(getFilesDir(), "white_stage.png");
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

    private void loadToFields() {
        etKey.setText(Config.amapKey(this));
        etAdcode.setText(Config.adcode(this));
        etPattern.setText(Config.pattern(this));
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
        tvTest.setTextColor(0xFF607D8B);
        SimpleDateFormat f = new SimpleDateFormat("MM-dd E HH:mm", Locale.CHINA);
        StringBuilder sb = new StringBuilder("接下来 ").append(ms.size()).append(" 次刷新:\n");
        for (long t : ms) {
            sb.append("  ").append(f.format(new Date(t))).append('\n');
        }
        tvTest.setText(sb.toString().trim());
    }

    private void triggerRefresh() {
        startService(new Intent(this, RefreshService.class).putExtra("manual", true));
        tvTest.setText("已触发刷新，取数+渲染+写入约需 20~60 秒…");
        tvTest.setTextColor(0xFF607D8B);
        for (int i = 1; i <= 12; i++) {
            final int delay = i * 5000;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshStatus();
                }
            }, delay);
        }
    }

    private void refreshStatus() {
        android.content.SharedPreferences p = Config.prefs(this);
        long next = p.getLong("next_at", -1);
        long last = p.getLong("last_at", 0);
        boolean ok = p.getBoolean("last_ok", false);
        String msg = p.getString("last_msg", "");
        StringBuilder sb = new StringBuilder();
        sb.append(next > 0 ? "下次刷新: " + fmt(next)
                : Config.autoRefresh(this) ? "下次刷新: 未布防" : "自动刷新已关闭");
        if (last > 0) {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
            sb.append("\n上次刷新: ").append(f.format(new Date(last))).append(' ')
                    .append(ok ? "✓ " : "✗ ").append(msg);
        }
        tvStatus.setText(sb.toString());
        tvStatus.setTextColor(ok || last == 0 ? 0xFF607D8B : 0xFFC62828);
    }

    private String fmt(long t) {
        return new SimpleDateFormat("MM-dd E HH:mm", Locale.CHINA).format(new Date(t));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private abstract static class SimpleText implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }
    }
}
