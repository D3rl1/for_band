package com.forband.auth;

/**
 * 登录设备标签：把 User-Agent 粗解析成固定的「浏览器 · 系统」两段式（docs/01 §1.4）。
 * 浏览器只认 Edge / Chrome / Firefox / Safari，系统只认 Windows / Android / iOS / macOS / Linux；
 * 只认出一段就只显示那一段；两段都认不出来 = 原样截断 + 标「未知设备」，**不许猜、不许编**。
 * 规格明令**不引 UA 解析库**——几十行关键词匹配就够。
 *
 * <p>⚠️ 判断顺序是 docs/01 点名的坑：Edge 的 UA 同时含 Chrome 与 Safari、Chrome 的 UA 也含
 * Safari；Android 的 UA 含 Linux、iOS 的 UA 含 like Mac OS X。顺序错了会出现
 * 「Edge 显示成 Chrome」「iPhone 显示成 macOS」——看起来能用、实则读错。
 * 所以浏览器按 Edge → Chrome → Firefox → Safari（先具体后泛化），
 * 系统按 Android → iOS → macOS → Windows → Linux。
 */
public final class UserAgentLabel {

    private static final int RAW_SNIPPET = 24;

    private UserAgentLabel() {
    }

    public static String parse(String rawUa) {
        String ua = rawUa == null ? "" : rawUa;
        String browser = firstContaining(ua, "Edg/", "Edge/", "Chrome/", "Firefox/", "Safari/");
        String os = firstContaining(ua,
                "Android", "Adr",
                "iPhone", "iPad", "iPod", "like Mac OS X",
                "Mac OS X", "Macintosh",
                "Windows",
                "Linux", "X11");
        if (browser != null && os != null) {
            return browser + " · " + os;
        }
        if (browser != null) {
            return browser;
        }
        if (os != null) {
            return os;
        }
        return "未知设备 · " + snippet(ua);
    }

    /** 按顺序返回第一个命中的关键词（命中即停——顺序即优先级）。 */
    private static String firstContaining(String ua, String... tokens) {
        for (String token : tokens) {
            if (ua.contains(token)) {
                return labelOf(token);
            }
        }
        return null;
    }

    private static String labelOf(String token) {
        return switch (token) {
            case "Edg/", "Edge/" -> "Edge";
            case "Chrome/" -> "Chrome";
            case "Firefox/" -> "Firefox";
            case "Safari/" -> "Safari";
            case "Android", "Adr" -> "Android";
            case "iPhone", "iPad", "iPod", "like Mac OS X" -> "iOS";
            case "Mac OS X", "Macintosh" -> "macOS";
            case "Windows" -> "Windows";
            case "Linux", "X11" -> "Linux";
            default -> token;
        };
    }

    /** 认不出来时"原样截断显示"：掐掉控制字符，留一小段原文。 */
    private static String snippet(String ua) {
        String clean = ua.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() > RAW_SNIPPET ? clean.substring(0, RAW_SNIPPET) + "…" : clean;
    }
}
