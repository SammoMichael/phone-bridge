package com.phonebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service that long-polls the queue server for commands and posts
 * results back. No inbound ports: the phone only makes outgoing HTTPS calls.
 */
public class PollService extends Service {

    public static volatile String status = "stopped";

    private static final String CHANNEL_ID = "pb";
    private static final int NOTIF_ID = 1;

    private volatile boolean running = false;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Phone Bridge", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Phone Bridge")
                .setContentText("Polling for remote commands")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build();
        startForeground(NOTIF_ID, n);
        if (worker == null) {
            running = true;
            worker = new Thread(this::loop, "pb-poll");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        status = "stopped";
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        while (running) {
            try {
                SharedPreferences p = getSharedPreferences("pb", MODE_PRIVATE);
                String base = p.getString("url", "").trim().replaceAll("/+$", "");
                String token = p.getString("token", "").trim();
                if (base.isEmpty() || token.isEmpty()) {
                    status = "not configured (set URL + token)";
                    sleep(5000);
                    continue;
                }
                if (BridgeService.getInstance() == null) {
                    status = "waiting for accessibility service to be enabled";
                    sleep(5000);
                    continue;
                }
                status = "polling " + base;
                JSONObject cmd = getNext(base, token);
                if (cmd == null) continue; // long-poll timed out, loop again
                String id = cmd.optString("id", "");
                String action = cmd.optString("action", "");
                status = "executing " + action;
                JSONObject res;
                if ("wait".equals(action)) {
                    long ms = cmd.optLong("ms", 1000);
                    if (ms < 0) ms = 0;
                    if (ms > 120000) ms = 120000;
                    Thread.sleep(ms);
                    res = new JSONObject().put("ok", true);
                } else {
                    res = BridgeService.getInstance().executeCommand(cmd);
                }
                postResult(base, token, id, res);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                status = "error: " + e.getMessage();
                sleep(5000);
            }
        }
        status = "stopped";
    }

    /** Long-polls GET /next; returns the command JSON or null on 204/timeout. */
    private JSONObject getNext(String base, String token) throws Exception {
        String url = base + "/next?token=" + URLEncoder.encode(token, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(40000); // server long-polls ~25s
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        try {
            if (code == 204) return null;
            if (code != 200) throw new Exception("GET /next -> HTTP " + code);
            return new JSONObject(readAll(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private void postResult(String base, String token, String id, JSONObject result) throws Exception {
        JSONObject body = new JSONObject();
        body.put("token", token);
        body.put("id", id);
        body.put("result", result);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/result").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code != 200) throw new Exception("POST /result -> HTTP " + code);
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        return sb.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
