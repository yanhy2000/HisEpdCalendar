package com.hi.epdcalendar;

import android.content.Context;
import android.util.Log;

import com.nlf.calendar.Holiday;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import com.nlf.calendar.util.HolidayUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

/**
 * 本地数据源（v1.0 起替代云端 /api/data）：
 *   system_info 本机时间；weather 高德直连（30min 缓存 + 过期兜底）；
 *   hitokoto 官方免费 API（无 key）；calendar lunar-java 本地计算。
 * 输出契约与 docs/api-data-sample.json 一致，模板无需改动。
 */
public final class DataProvider {

    private static final String TAG = "EpdCal";
    private static final String AMAP_BASE = "https://restapi.amap.com/v3/weather/weatherInfo";
    private static final String HITOKOTO_URL =
            "https://v1.hitokoto.cn/?c=i&c=f&c=e&c=a&max_length=25&encode=json";
    private static final long WEATHER_CACHE_MS = 30 * 60 * 1000L;

    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] HEADERS = {"一", "二", "三", "四", "五", "六", "日"};

    private DataProvider() {}

    public static JSONObject build(Context ctx) throws Exception {
        JSONObject root = new JSONObject();
        root.put("system_info", systemInfo());
        try {
            JSONObject w = weather(ctx);
            if (w != null) {
                root.put("weather", w);
            }
        } catch (Throwable t) {
            DLog.w("天气获取失败（本次无天气块）: " + t);
        }
        root.put("hitokoto", hitokoto(ctx));
        root.put("calendar", calendar());
        return root;
    }

    // ======================== HTTP ========================

    private static String httpGet(String urlStr, int connectMs, int readMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            int code = conn.getResponseCode();
            InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code + ": " + body);
            }
            return body;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 小重试：WiFi 刚关联时 DNS 可能尚未就绪 */
    private static String httpGetRetry(String urlStr, int attempts) throws Exception {
        Exception last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                return httpGet(urlStr, 10_000, 20_000);
            } catch (Exception e) {
                last = e;
                if (i < attempts) {
                    Thread.sleep(3000);
                }
            }
        }
        throw last;
    }

    private static String readAll(InputStream in) throws Exception {
        InputStreamReader r = new InputStreamReader(in, "UTF-8");
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        return sb.toString();
    }

    private static String readCache(Context ctx, String name) {
        try {
            File f = new File(ctx.getFilesDir(), name);
            if (!f.exists()) {
                return null;
            }
            return readAll(new java.io.FileInputStream(f));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCache(Context ctx, String name, String content) {
        try {
            java.io.FileOutputStream fos = ctx.openFileOutput(name, Context.MODE_PRIVATE);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            DLog.w("写缓存失败 " + name + ": " + t);
        }
    }

    // ======================== system_info ========================

    private static JSONObject systemInfo() throws Exception {
        Calendar now = Calendar.getInstance();
        JSONObject o = new JSONObject();
        o.put("time", String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)));
        o.put("time_sec", String.format("%02d:%02d:%02d", now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE), now.get(Calendar.SECOND)));
        o.put("date", String.format("%04d-%02d-%02d", now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)));
        o.put("year", String.format("%04d", now.get(Calendar.YEAR)));
        o.put("month", String.format("%02d", now.get(Calendar.MONTH) + 1));
        o.put("day", String.format("%02d", now.get(Calendar.DAY_OF_MONTH)));
        o.put("weekday", WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1 == 0 ? 6 : now.get(Calendar.DAY_OF_WEEK) - 2]);
        o.put("timestamp", System.currentTimeMillis() / 1000);
        return o;
    }

    // ======================== weather（高德直连） ========================

    private static JSONObject weather(Context ctx) throws Exception {
        String key = Config.amapKey(ctx);
        if (key.isEmpty()) {
            DLog.i("未配置高德 Key，跳过天气");
            return null;
        }
        String adcode = Config.adcode(ctx);
        if (adcode.isEmpty()) {
            adcode = autoAdcode(ctx, key); // 留空 = 按请求 IP 自动定位
        }
        if (adcode == null || adcode.isEmpty()) {
            DLog.w("城市代码未定（自动定位失败且未手动填写），跳过天气");
            return null;
        }

        // 30 分钟内直接用缓存（手动频繁刷新不烧配额）
        String cached = readCache(ctx, "weather_cache.json");
        long cachedAt = 0;
        JSONObject cachedData = null;
        try {
            if (cached != null) {
                JSONObject c = new JSONObject(cached);
                cachedAt = c.optLong("time", 0);
                cachedData = c.optJSONObject("data");
            }
        } catch (Throwable ignored) {
        }
        if (cachedData != null && System.currentTimeMillis() - cachedAt < WEATHER_CACHE_MS) {
            return cachedData;
        }

        JSONObject live = null;
        JSONArray forecast = null;
        try {
            JSONObject base = new JSONObject(httpGetRetry(AMAP_BASE
                    + "?key=" + key + "&city=" + adcode + "&extensions=base&output=JSON", 2));
            if (!"1".equals(base.optString("status"))) {
                throw new IllegalStateException("高德实况返回: " + base.optString("infocode", "?"));
            }
            JSONArray lives = base.optJSONArray("lives");
            if (lives != null && lives.length() > 0) {
                live = lives.getJSONObject(0);
            }

            JSONObject all = new JSONObject(httpGetRetry(AMAP_BASE
                    + "?key=" + key + "&city=" + adcode + "&extensions=all&output=JSON", 2));
            JSONArray forecasts = all.optJSONArray("forecasts");
            if (forecasts != null && forecasts.length() > 0) {
                JSONArray casts = forecasts.getJSONObject(0).optJSONArray("casts");
                if (casts != null) {
                    forecast = new JSONArray();
                    for (int i = 0; i < Math.min(4, casts.length()); i++) {
                        JSONObject c = casts.getJSONObject(i);
                        JSONObject d = new JSONObject();
                        d.put("date", c.optString("date"));
                        d.put("week", amapWeek(c.optString("week")));
                        d.put("dayweather", c.optString("dayweather"));
                        d.put("dayicon", WeatherIcons.day(c.optString("dayweather")));
                        d.put("nightweather", c.optString("nightweather"));
                        d.put("nighticon", WeatherIcons.night(c.optString("nightweather")));
                        d.put("daytemp", c.optString("daytemp"));
                        d.put("nighttemp", c.optString("nighttemp"));
                        forecast.put(d);
                    }
                }
            }
        } catch (Exception e) {
            DLog.w("高德请求失败: " + e);
        }

        if (live == null && cachedData != null) {
            DLog.i("天气请求失败，回退旧缓存");
            return cachedData;
        }
        if (live == null) {
            return null;
        }

        JSONObject out = new JSONObject();
        out.put("city", live.optString("city"));
        out.put("province", live.optString("province"));
        JSONObject l = new JSONObject();
        l.put("weather", live.optString("weather"));
        l.put("temperature", live.optString("temperature"));
        l.put("humidity", live.optString("humidity"));
        l.put("winddirection", live.optString("winddirection"));
        l.put("windpower", live.optString("windpower"));
        l.put("reporttime", live.optString("reporttime"));
        l.put("icon", WeatherIcons.day(live.optString("weather")));
        out.put("live", l);
        if (forecast != null) {
            out.put("forecast", forecast);
        }

        JSONObject wrap = new JSONObject();
        wrap.put("time", System.currentTimeMillis());
        wrap.put("data", out);
        writeCache(ctx, "weather_cache.json", wrap.toString());
        return out;
    }

    /**
     * 高德 IP 定位取 adcode（v3/ip 不传 ip 参数即按请求方 IP）。
     * 结果缓存 7 天（filesDir/ip_adcode.json），几乎不占配额；失败返回 ""。
     */
    private static String autoAdcode(Context ctx, String key) {
        final long ttl = 7L * 24 * 60 * 60 * 1000;
        try {
            String cached = readCache(ctx, "ip_adcode.json");
            if (cached != null) {
                JSONObject c = new JSONObject(cached);
                String v = c.optString("adcode", "");
                if (v.matches("\\d{4,6}")
                        && System.currentTimeMillis() - c.optLong("time", 0) < ttl) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            JSONObject r = new JSONObject(httpGetRetry(
                    "https://restapi.amap.com/v3/ip?key=" + key, 2));
            String v = r.optString("adcode", "");
            if ("1".equals(r.optString("status")) && v.matches("\\d{4,6}")) {
                JSONObject c = new JSONObject();
                c.put("time", System.currentTimeMillis());
                c.put("adcode", v);
                writeCache(ctx, "ip_adcode.json", c.toString());
                DLog.i("IP 定位城市成功 adcode=" + v + " province=" + r.optString("province"));
                return v;
            }
            DLog.w("IP 定位失败: " + r);
        } catch (Throwable t) {
            DLog.w("IP 定位异常: " + t);
        }
        return "";
    }

    private static String amapWeek(String digit) {
        if ("1".equals(digit)) return "周一";
        if ("2".equals(digit)) return "周二";
        if ("3".equals(digit)) return "周三";
        if ("4".equals(digit)) return "周四";
        if ("5".equals(digit)) return "周五";
        if ("6".equals(digit)) return "周六";
        if ("7".equals(digit)) return "周日";
        return "";
    }

    // ======================== hitokoto ========================

    private static JSONObject hitokoto(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            JSONObject j = new JSONObject(httpGetRetry(HITOKOTO_URL, 2));
            out.put("hitokoto", j.optString("hitokoto", ""));
            out.put("from", j.optString("from", ""));
            out.put("from_who", j.isNull("from_who") ? "" : j.optString("from_who", ""));
            writeCache(ctx, "hitokoto_cache.json", out.toString());
        } catch (Throwable t) {
            DLog.w("一言获取失败: " + t);
            String cached = readCache(ctx, "hitokoto_cache.json");
            try {
                if (cached != null) {
                    return new JSONObject(cached);
                }
            } catch (Throwable ignored) {
            }
            try {
                out.put("hitokoto", "加载中...");
                out.put("from", "");
                out.put("from_who", "");
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    // ======================== calendar（lunar-java 本地计算） ========================

    private static JSONObject calendar() throws Exception {
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        int month = now.get(Calendar.MONTH) + 1;
        int today = now.get(Calendar.DAY_OF_MONTH);

        // ISO 周号（周一为首、首周≥4 天）
        Calendar wk = new GregorianCalendar(year, month - 1, today);
        wk.setFirstDayOfWeek(Calendar.MONDAY);
        wk.setMinimalDaysInFirstWeek(4);
        String weekFormat = String.format("%02d%02d", year % 100, wk.get(Calendar.WEEK_OF_YEAR));

        Calendar first = new GregorianCalendar(year, month - 1, 1);
        int adjustedFirstDay = first.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        if (adjustedFirstDay < 0) {
            adjustedFirstDay += 7;
        }
        int monthDays = new GregorianCalendar(year, month - 1, 1)
                .getActualMaximum(Calendar.DAY_OF_MONTH);

        JSONObject o = new JSONObject();
        o.put("year", year);
        o.put("month", month);
        o.put("today", today);
        JSONArray headers = new JSONArray();
        for (String h : HEADERS) {
            headers.put(h);
        }
        o.put("weekday_headers", headers);

        JSONObject daysData = new JSONObject();
        String lunarMonthDate = "";
        String lunarYearZodiac = "";
        String todayGanzhi = "";
        JSONArray todayYi = new JSONArray();
        JSONArray todayJi = new JSONArray();

        for (int day = 1; day <= monthDays; day++) {
            Lunar lunar = Solar.fromYmd(year, month, day).getLunar();
            String display = lunar.getDay() == 1
                    ? lunar.getMonthInChinese() + "月" : lunar.getDayInChinese();
            String type = "lunar";
            String jieqi = lunar.getJieQi();
            if (jieqi != null && !jieqi.isEmpty()) {
                display = jieqi;
                type = "jieqi";
            }
            JSONObject info = new JSONObject();
            info.put("lunar", display);
            info.put("type", type);
            Holiday holiday = HolidayUtil.getHoliday(year, month, day);
            info.put("badge", holiday == null ? JSONObject.NULL
                    : (holiday.isWork() ? "ban" : "xiu"));
            daysData.put(String.valueOf(day), info);

            if (day == today) {
                lunarMonthDate = lunar.getMonthInChinese() + "月" + lunar.getDayInChinese();
                lunarYearZodiac = lunar.getYearInGanZhi() + "年 " + lunar.getYearShengXiao();
                todayGanzhi = lunar.getYearInGanZhi() + "年 "
                        + lunar.getMonthInGanZhi() + "月 " + lunar.getDayInGanZhi() + "日";
                List<String> yi = lunar.getDayYi();
                for (int i = 0; i < Math.min(2, yi.size()); i++) {
                    todayYi.put(yi.get(i));
                }
                List<String> ji = lunar.getDayJi();
                for (int i = 0; i < Math.min(2, ji.size()); i++) {
                    todayJi.put(ji.get(i));
                }
            }
        }

        o.put("days_data", daysData);
        o.put("month_name", year + "年" + month + "月");
        o.put("lunar_month_date", lunarMonthDate);
        o.put("lunar_year_zodiac", lunarYearZodiac);
        o.put("week_format", weekFormat);
        o.put("start_day", "monday");
        o.put("adjusted_first_day", adjustedFirstDay);
        o.put("month_days", monthDays);
        o.put("today_ganzhi", todayGanzhi);
        o.put("today_yi", todayYi);
        o.put("today_ji", todayJi);

        // 节气倒计时：向后扫 120 天找第一个节气日
        String jieqiName = "";
        int jieqiDays = 0;
        Calendar scan = new GregorianCalendar(year, month - 1, today);
        for (int i = 1; i <= 120; i++) {
            scan.add(Calendar.DAY_OF_MONTH, 1);
            String jq = Solar.fromYmd(scan.get(Calendar.YEAR),
                    scan.get(Calendar.MONTH) + 1, scan.get(Calendar.DAY_OF_MONTH))
                    .getLunar().getJieQi();
            if (jq != null && !jq.isEmpty()) {
                jieqiName = jq;
                jieqiDays = i;
                break;
            }
        }
        o.put("next_jieqi_name", jieqiName);
        o.put("next_jieqi_days", jieqiDays);

        // 节假日倒计时：本年度+下一年度的法定假日（非调休），取每个假期首日
        String holidayName = "";
        int holidayDays = 0;
        long todayEpoch = new GregorianCalendar(year, month - 1, today).getTimeInMillis();
        outer:
        for (int y = year; y <= year + 1; y++) {
            List<Holiday> list = new ArrayList<>(HolidayUtil.getHolidays(y));
            list.sort((a, b) -> a.getDay().compareTo(b.getDay()));
            String prevName = null;
            long prevTime = Long.MIN_VALUE;
            for (Holiday h : list) {
                if (h.isWork()) {
                    continue; // 调休上班日不算假期
                }
                String ymd = h.getDay(); // yyyy-MM-dd
                GregorianCalendar hc = new GregorianCalendar(
                        Integer.parseInt(ymd.substring(0, 4)),
                        Integer.parseInt(ymd.substring(5, 7)) - 1,
                        Integer.parseInt(ymd.substring(8, 10)));
                long t = hc.getTimeInMillis();
                boolean sameGroup = prevName != null && h.getName().equals(prevName)
                        && t - prevTime <= 2L * 86400_000L; // 同名且相邻
                if (!sameGroup && t > todayEpoch) {
                    holidayName = h.getName();
                    holidayDays = (int) ((t - todayEpoch) / 86400_000L);
                    break outer;
                }
                if (!sameGroup) {
                    prevName = h.getName();
                }
                prevTime = t;
            }
        }
        o.put("next_holiday_name", holidayName);
        o.put("next_holiday_days", holidayDays);
        return o;
    }
}
