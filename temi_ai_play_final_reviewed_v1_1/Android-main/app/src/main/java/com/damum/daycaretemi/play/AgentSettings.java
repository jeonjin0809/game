package com.damum.daycaretemi.play;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URL;

/** The API key is never stored on temi; the Android app knows only the laptop LAN URL. */
public final class AgentSettings {
    private static final String PREFS = "memory_agent_settings";
    private static final String HOST = "host_url";
    private AgentSettings() {}
    public static String getUrl(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HOST, "");
    }
    public static void setUrl(Context c, String value) {
        String normalized = normalize(value);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(HOST, normalized).apply();
    }
    public static String normalize(String value) {
        String url = value == null ? "" : value.trim().replaceAll("/+$", "");
        if (url.isEmpty()) return "";
        try {
            URL parsed = new URL(url);
            if (!"http".equals(parsed.getProtocol()) && !"https".equals(parsed.getProtocol()))
                throw new IllegalArgumentException();
            if (parsed.getHost().isEmpty() || !parsed.getPath().isEmpty()
                    || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getRef() != null)
                throw new IllegalArgumentException();
            return url;
        } catch (Exception e) {
            throw new IllegalArgumentException("예: http://192.168.0.15:8765 형식으로 입력해 주세요.");
        }
    }
}
