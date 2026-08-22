package com.hi.epdcalendar;

import java.util.HashMap;
import java.util.Map;

/**
 * 高德天气文本 → 和风风格图标代码（与 assets/template/icons/*.svg 对应）。
 * 模板与图标表均消费和风代码；夜间槽位对晴/多云族使用 15x 变体（模板仅用
 * dayicon 与实况 icon，nighticon 保留以维持数据契约兼容）。
 */
final class WeatherIcons {

    private WeatherIcons() {}

    private static final Map<String, String> DAY = new HashMap<>();

    static {
        DAY.put("晴", "100");
        DAY.put("少云", "102");
        DAY.put("晴间多云", "103");
        DAY.put("多云", "101");
        DAY.put("阴", "104");
        DAY.put("阵雨", "300");
        DAY.put("雷阵雨", "301");
        DAY.put("雷阵雨伴有冰雹", "301");
        DAY.put("小雨", "305");
        DAY.put("毛毛雨", "305");
        DAY.put("中雨", "306");
        DAY.put("大雨", "307");
        DAY.put("暴雨", "310");
        DAY.put("大暴雨", "311");
        DAY.put("特大暴雨", "312");
        DAY.put("冻雨", "399");
        DAY.put("雨夹雪", "404");
        DAY.put("小雪", "400");
        DAY.put("中雪", "401");
        DAY.put("大雪", "402");
        DAY.put("暴雪", "403");
        DAY.put("雾", "501");
        DAY.put("浓雾", "502");
        DAY.put("强浓雾", "503");
        DAY.put("轻雾", "500");
        DAY.put("大雾", "501");
        DAY.put("轻度霾", "507");
        DAY.put("中度霾", "507");
        DAY.put("重度霾", "509");
        DAY.put("严重霾", "509");
        DAY.put("浮尘", "999");
        DAY.put("扬沙", "999");
        DAY.put("沙尘暴", "999");
        DAY.put("强沙尘暴", "999");
        DAY.put("未知", "999");
        DAY.put("热", "900");
        DAY.put("冷", "901");
    }

    /** 白天图标码；未知文本回 999 */
    static String day(String amapText) {
        if (amapText == null) {
            return "999";
        }
        String v = DAY.get(amapText.trim());
        return v != null ? v : "999";
    }

    /** 夜间图标码：晴/多云族换 15x 变体，其余同白天 */
    static String night(String amapText) {
        String d = day(amapText);
        if ("100".equals(d)) {
            return "150";
        }
        if ("101".equals(d)) {
            return "151";
        }
        if ("102".equals(d)) {
            return "152";
        }
        if ("103".equals(d)) {
            return "153";
        }
        if ("300".equals(d)) {
            return "350";
        }
        return d;
    }
}
