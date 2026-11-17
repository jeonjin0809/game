package com.damum.daycaretemi.play;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** No identifying child data, images or audio are sent to the laptop. */
public final class MemoryAgentClient {
    public interface Callback { void onComplete(JSONObject body, String failure); }
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void close() { executor.shutdownNow(); }

    public void health(String url, Callback callback) {
        request(url, "/health", null, callback);
    }

    public void plan(String url, List<MemoryGame.RoundResult> results,
                     Callback callback) {
        plan(url, results, null, callback);
    }

    public void plan(String url, List<MemoryGame.RoundResult> results,
                     String approvedFollowUpMission, Callback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("round_index", results.size());
            body.put("history", historyJson(results));
            if (approvedFollowUpMission != null) body.put("follow_up_target", approvedFollowUpMission);
            request(url, "/plan", body, callback);
        } catch (Exception e) { callback.onComplete(null, "요청 준비 실패"); }
    }

    public void report(String url, List<MemoryGame.RoundResult> results,
                       Callback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("history", historyJson(results));
            request(url, "/report", body, callback);
        } catch (Exception e) { callback.onComplete(null, "보고서 요청 준비 실패"); }
    }

    private static JSONArray historyJson(List<MemoryGame.RoundResult> results) throws Exception {
        JSONArray history = new JSONArray();
        for (MemoryGame.RoundResult r : results) {
            JSONObject row = new JSONObject();
            row.put("round", r.roundNumber);
            row.put("category", r.mission.equals("story") ? "stories"
                    : r.category.equals("동물") ? "animals" : "fruits");
            row.put("mission", r.mission);
            row.put("difficulty", r.shownItems);
            row.put("correct", r.correct);
            row.put("hints", r.hintsUsed);
            row.put("examples", r.examplesUsed);
            row.put("waiting_prompts", r.waitingPrompts);
            row.put("response_seconds", Math.min(600, r.responseMillis / 1000));
            history.put(row);
        }
        return history;
    }

    private void request(String base, String path, JSONObject payload, Callback callback) {
        if (base == null || base.isEmpty()) {
            main.post(() -> callback.onComplete(null, "AI 서버 미설정"));
            return;
        }
        executor.execute(() -> {
            HttpURLConnection conn = null;
            JSONObject response = null;
            String failure = null;
            try {
                conn = (HttpURLConnection) new URL(base + path).openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");
                if (payload != null) {
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (conn.getResponseCode() != 200) throw new IllegalStateException("서버 응답 " + conn.getResponseCode());
                InputStream stream = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder raw = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && raw.length() < 8192) raw.append(line);
                response = new JSONObject(raw.toString());
            } catch (Exception e) {
                failure = "AI 서버 연결 실패 (오프라인으로 계속 진행)";
            } finally {
                if (conn != null) conn.disconnect();
            }
            JSONObject result = response;
            String error = failure;
            main.post(() -> callback.onComplete(result, error));
        });
    }
}
