package com.hi.epdcalendar;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 调试日志（控制台「调试日志」开关控制，adb 也可经 ACTION_CONFIG --ez debug 切换）：
 * 进程内缓冲，刷新结束时一次性经 su 追加到 /sdcard/eink_clock/debug.log
 * （本机 ROM 禁止第三方应用直写 /sdcard，与壁纸落位同一套 su 通道）。
 * 开关关闭时 flush 直接丢弃缓冲，无任何落盘开销；超 256KB 轮转为 .old。
 * 心跳日志 .wd_log 由引擎侧看门狗常开记录，不受本开关控制。
 */
public final class DLog {
    private static final String TAG = "EpdCal";
    private static final String PATH = "/sdcard/eink_clock/debug.log";
    private static final long MAX_BYTES = 256L * 1024;

    private static final List<String> BUF = new ArrayList<>();

    private DLog() {}

    public static void i(String msg) {
        Log.i(TAG, msg);
        BUF.add(System.currentTimeMillis() + " " + msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        BUF.add(System.currentTimeMillis() + " [W] " + msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        BUF.add(System.currentTimeMillis() + " [E] " + msg);
    }

    /** 主线程安全的落盘（Activity/Receiver 里用；su 可能慢，绝不能卡 UI） */
    public static void flushAsync(final Context ctx) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                flush(ctx);
            }
        }, "EpdCal-dlog").start();
    }

    /** 流水线/接收器收尾时调用：开关开启则带时间戳落盘，随后清空缓冲 */
    public static void flush(Context ctx) {
        try {
            if (BUF.isEmpty()) {
                return;
            }
            if (!Config.debugLog(ctx)) {
                BUF.clear();
                return;
            }
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
            StringBuilder sb = new StringBuilder();
            for (String line : BUF) {
                int sp = line.indexOf(' ');
                long ts = Long.parseLong(line.substring(0, sp));
                sb.append(f.format(new Date(ts))).append(line.substring(sp)).append('\n');
            }
            BUF.clear();
            File stage = new File(ctx.getFilesDir(), "dlog_stage");
            FileOutputStream fos = new FileOutputStream(stage);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            String rotate = new File(PATH).length() > MAX_BYTES
                    ? ("mv -f " + PATH + " " + PATH + ".old; ") : "";
            Su.run(rotate + "cat '" + stage.getAbsolutePath() + "' >> " + PATH);
        } catch (Throwable t) {
            Log.w(TAG, "调试日志落盘失败: " + t);
            BUF.clear();
        }
    }
}
