package com.phonebridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes remote commands: gestures, text entry, global keys, app launch,
 * accessibility-tree dumps and screenshots. All work runs on the main thread
 * via executeCommand(); callers block on the returned result.
 */
public class BridgeService extends AccessibilityService {

    private static volatile BridgeService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int MAX_DUMP_NODES = 500;
    private static final int MAX_DUMP_DEPTH = 25;

    public static BridgeService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: the service is driven by explicit commands, not events.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /**
     * Execute one command JSON on the main thread and block (up to 60s)
     * for the result. The "wait" action is handled by the caller instead.
     */
    public JSONObject executeCommand(final JSONObject cmd) {
        final AtomicReference<JSONObject> out = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                out.set(runAction(cmd));
            } catch (Exception e) {
                out.set(err("exception: " + e.getMessage()));
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("interrupted");
        }
        JSONObject r = out.get();
        return r != null ? r : err("timed out waiting for main thread");
    }

    private JSONObject runAction(JSONObject cmd) throws Exception {
        String action = cmd.optString("action", "");
        switch (action) {
            case "tap":
                return doTap(cmd.optInt("x", -1), cmd.optInt("y", -1));
            case "swipe":
                return doSwipe(cmd.optInt("x1", -1), cmd.optInt("y1", -1),
                        cmd.optInt("x2", -1), cmd.optInt("y2", -1),
                        cmd.optLong("ms", 400));
            case "type":
                return doType(cmd.optString("text", ""));
            case "key":
                return doKey(cmd.optString("name", ""));
            case "open":
                return doOpen(cmd.optString("package", ""));
            case "dump":
                return doDump();
            case "screenshot":
                return doScreenshot();
            default:
                return err("unknown action: " + action);
        }
    }

    // ---------- gestures ----------

    private JSONObject doTap(int x, int y) throws Exception {
        if (x < 0 || y < 0) return err("tap needs x,y");
        Path p = new Path();
        p.moveTo(x, y);
        return doGesture(p, 60);
    }

    private JSONObject doSwipe(int x1, int y1, int x2, int y2, long ms) throws Exception {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return err("swipe needs x1,y1,x2,y2");
        if (ms < 50) ms = 50;
        if (ms > 5000) ms = 5000;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return doGesture(p, ms);
    }

    private JSONObject doGesture(Path path, long durationMs) throws Exception {
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean(false);
        dispatchGesture(gd, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                completed.set(true);
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, null);
        latch.await(8, TimeUnit.SECONDS);
        return completed.get() ? ok() : err("gesture cancelled or timed out");
    }

    // ---------- text entry ----------

    private JSONObject doType(String text) {
        if (text.isEmpty()) return err("type needs text");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return err("no active window");
        AccessibilityNodeInfo target = findEditableFocused(root);
        if (target == null) return err("no focused editable field (tap the field first)");
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean done = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!done) {
            // Fallback: clipboard + paste (works for CJK text too).
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("pb", text));
                done = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            }
        }
        return done ? ok() : err("set-text and paste both failed");
    }

    private AccessibilityNodeInfo findEditableFocused(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) return node;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findEditableFocused(child);
            if (found != null) return found;
        }
        return null;
    }

    // ---------- global keys ----------

    private JSONObject doKey(String name) {
        int action;
        switch (name) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            default: return err("unknown key '" + name + "' (back|home|recents)");
        }
        return performGlobalAction(action) ? ok() : err("global action failed");
    }

    // ---------- app launch ----------

    private JSONObject doOpen(String pkg) {
        if (pkg.isEmpty()) return err("open needs package");
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return err("no launch intent for " + pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return ok();
    }

    // ---------- accessibility tree dump ----------

    private JSONObject doDump() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return err("no active window");
        JSONArray arr = new JSONArray();
        int[] count = {0};
        walk(root, arr, count, 0);
        JSONObject r = ok();
        r.put("nodes", arr);
        r.put("truncated", count[0] >= MAX_DUMP_NODES);
        return r;
    }

    private void walk(AccessibilityNodeInfo n, JSONArray arr, int[] count, int depth) {
        if (n == null || count[0] >= MAX_DUMP_NODES || depth > MAX_DUMP_DEPTH) return;
        try {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            boolean hasText = t != null && t.length() > 0;
            boolean hasDesc = d != null && d.length() > 0;
            StringBuilder f = new StringBuilder();
            if (n.isClickable()) f.append('C');
            if (n.isEditable()) f.append('E');
            if (n.isFocused()) f.append('F');
            if (n.isScrollable()) f.append('S');
            // Skip pure layout containers to keep dumps small; still walk children.
            boolean useful = hasText || hasDesc || f.length() > 0 || n.getChildCount() == 0;
            if (useful) {
                JSONObject o = new JSONObject();
                if (hasText) o.put("t", t.toString().substring(0, Math.min(120, t.length())));
                if (hasDesc) o.put("d", d.toString().substring(0, Math.min(120, d.length())));
                CharSequence cls = n.getClassName();
                String cs = cls != null ? cls.toString() : "";
                int dot = cs.lastIndexOf('.');
                o.put("c", dot >= 0 ? cs.substring(dot + 1) : cs);
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                o.put("b", new JSONArray(new int[]{b.left, b.top, b.right, b.bottom}));
                if (f.length() > 0) o.put("f", f.toString());
                arr.put(o);
                count[0]++;
            }
            int kids = n.getChildCount();
            for (int i = 0; i < kids; i++) {
                walk(n.getChild(i), arr, count, depth + 1);
            }
        } catch (Exception ignored) {
            // Skip unreadable nodes.
        }
    }

    // ---------- screenshot (API 30+) ----------

    private JSONObject doScreenshot() {
        if (Build.VERSION.SDK_INT < 30) {
            return err("screenshot needs Android 11 (API 30)+");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<JSONObject> out = new AtomicReference<>();
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult res) {
                        try {
                            HardwareBuffer hb = res.getHardwareBuffer();
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                            Bitmap scaled = scaleDown(bmp, 720);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos);
                            if (scaled != bmp) scaled.recycle();
                            if (bmp != null) bmp.recycle();
                            hb.close();
                            JSONObject r = ok();
                            r.put("format", "jpeg");
                            r.put("image", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
                            out.set(r);
                        } catch (Exception e) {
                            out.set(err("encode failed: " + e.getMessage()));
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        out.set(err("screenshot failed, code=" + errorCode));
                        latch.countDown();
                    }
                });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("interrupted");
        }
        JSONObject r = out.get();
        return r != null ? r : err("screenshot timed out");
    }

    private static Bitmap scaleDown(Bitmap bmp, int maxW) {
        if (bmp == null || bmp.getWidth() <= maxW) return bmp;
        float s = (float) maxW / bmp.getWidth();
        return Bitmap.createScaledBitmap(bmp, maxW, Math.round(bmp.getHeight() * s), true);
    }

    // ---------- helpers ----------

    private static JSONObject ok() {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", true);
        } catch (Exception ignored) {
        }
        return r;
    }

    private static JSONObject err(String msg) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", false);
            r.put("error", msg);
        } catch (Exception ignored) {
        }
        return r;
    }
}
