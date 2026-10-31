package com.maatv.dump;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Runs on the TV through app_process with the system's uiautomator.jar on the classpath
 * (the hidden classes are reached by reflection). Two jobs:
 *
 *   Dump <out.xml> <width> <height>
 *     Writes the screen like `uiautomator dump`, but without waiting for the screen to go idle
 *     first. Apps with animated backgrounds (ZEE5's profile screen) never go idle, so the stock
 *     command fails on them.
 *
 *   Dump input <ops>
 *     Sends taps and keys quickly from one process. Each `input` shell command starts a new Java
 *     process (about a second on this TV), which made typing on an app's own keyboard very slow.
 *     ops is comma-separated: t:x:y (tap), k:code (key), s:ms (sleep).
 *
 *   Dump type <keys>
 *     Types on an app's own on-screen keyboard while watching the search box. keys is
 *     comma-separated c:x:y (the character, '_' for space, '~' for the Delete key, and where its
 *     key is; put '~' first). A tap on a TV
 *     keyboard either presses the key or only highlights it, and some apps (SonyLIV) move the
 *     highlight away while results refresh, so each key is tapped until its letter appears.
 *     Prints the box's final text.
 *
 *   Dump serve
 *     Stays running and reads commands from stdin, one per line ("dump <out> <w> <h>",
 *     "input <ops>", "type <keys>"), answering each with its output and a "##DONE##" line.
 *     Starting this program takes about a second on the TV; serving avoids paying that on
 *     every screen read.
 */
public class Dump {
    public static void main(String[] args) throws Exception {
        Class<?> wrapperClass = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
        Object wrapper = wrapperClass.getDeclaredConstructor().newInstance();
        wrapperClass.getMethod("connect").invoke(wrapper);
        try {
            UiAutomation ua = (UiAutomation) wrapperClass.getMethod("getUiAutomation").invoke(wrapper);
            if (args.length > 0 && args[0].equals("serve")) {
                serve(ua);
            } else if (args.length > 0 && args[0].equals("input")) {
                input(ua, args.length > 1 ? args[1] : "");
            } else if (args.length > 0 && args[0].equals("type")) {
                type(ua, args.length > 1 ? args[1] : "");
            } else {
                dump(ua, args);
            }
        } finally {
            wrapperClass.getMethod("disconnect").invoke(wrapper);
        }
    }

    private static void serve(UiAutomation ua) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("##READY##");
        System.out.flush();
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.equals("quit")) break;
            String[] parts = line.split(" ", 2);
            String rest = parts.length > 1 ? parts[1] : "";
            try {
                switch (parts[0]) {
                    case "dump":
                        dump(ua, rest.split(" "));
                        break;
                    case "input":
                        input(ua, rest);
                        break;
                    case "type":
                        type(ua, rest);
                        break;
                    default:
                        System.out.println("ERROR: unknown command " + parts[0]);
                }
            } catch (Throwable e) {
                System.out.println("ERROR: " + e);
            }
            System.out.println("##DONE##");
            System.out.flush();
        }
    }

    private static void dump(UiAutomation ua, String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "/sdcard/maatv_ui.xml";
        int width = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : 1080;
        AccessibilityNodeInfo root = null;
        for (int i = 0; i < 30 && root == null; i++) {
            root = ua.getRootInActiveWindow();
            if (root == null) Thread.sleep(100);
        }
        if (root == null) {
            System.out.println("ERROR: no active window");
            return;
        }
        Class<?> dumper = Class.forName("com.android.uiautomator.core.AccessibilityNodeInfoDumper");
        dumper.getMethod("dumpWindowToFile", AccessibilityNodeInfo.class, File.class, int.class, int.class, int.class)
            .invoke(null, root, new File(out), 0, width, height);
        System.out.println("UI hierchary dumped to: " + out);
    }

    private static void input(UiAutomation ua, String ops) throws Exception {
        for (String op : ops.split(",")) {
            String[] p = op.trim().split(":");
            switch (p[0]) {
                case "t":
                    tap(ua, Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                    break;
                case "k":
                    key(ua, Integer.parseInt(p[1]));
                    break;
                case "s":
                    Thread.sleep(Long.parseLong(p[1]));
                    break;
                default:
                    break;
            }
        }
        System.out.println("input done");
    }

    private static void type(UiAutomation ua, String keys) throws Exception {
        box = null; // a new search screen each time when serving
        float[] del = null;
        StringBuilder want = new StringBuilder();
        for (String item : keys.split(",")) {
            String[] p = item.split(":");
            if (p.length < 3 || p[0].isEmpty()) continue;
            float x = Float.parseFloat(p[1]);
            float y = Float.parseFloat(p[2]);
            if (p[0].charAt(0) == '~') {
                del = new float[] {x, y};
                continue;
            }
            want.append(p[0].charAt(0) == '_' ? ' ' : p[0].charAt(0));
            String target = want.toString().toLowerCase();
            for (int attempt = 0; attempt < 5; attempt++) {
                String cur = fieldText(ua);
                if (cur == null) {
                    // No readable box: tap twice (highlight, then press) and hope.
                    tap(ua, x, y);
                    Thread.sleep(250);
                    tap(ua, x, y);
                    Thread.sleep(250);
                    break;
                }
                if (cur.toLowerCase().equals(target)) break;
                if (cur.length() >= target.length()) {
                    // A tap counted twice. Delete back to what we want, if we know where Delete is.
                    if (del == null || !cur.toLowerCase().startsWith(target)) {
                        System.out.println("overshoot: '" + cur + "'");
                        return;
                    }
                    tap(ua, del[0], del[1]);
                    waitForChange(ua, cur, del[0], del[1]);
                    continue;
                }
                tap(ua, x, y);
                waitForChange(ua, cur, x, y);
            }
            // Progress, which also tells the phone the TV is still busy (not stuck).
            System.out.println(".");
            System.out.flush();
        }
        System.out.println("typed: " + fieldText(ua));
    }

    /**
     * Waits up to ~1 s for the text box to show something other than [before]. Returns early
     * when the tap at (x, y) only highlighted its key, so the caller can tap again straight away.
     */
    private static void waitForChange(UiAutomation ua, String before, float x, float y) throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(100);
            String now = fieldText(ua);
            if (now != null && !now.equals(before)) {
                Thread.sleep(80);
                return;
            }
            if (i >= 2 && isHighlighted(ua, x, y)) return;
        }
    }

    private static boolean isHighlighted(UiAutomation ua, float x, float y) {
        AccessibilityNodeInfo f = ua.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        f.getBoundsInScreen(r);
        return r.contains((int) x, (int) y);
    }

    private static AccessibilityNodeInfo box;

    /** Text of the first text box on screen, "" while it only shows its hint, null if none. */
    private static String fieldText(UiAutomation ua) {
        // Finding the box walks the whole screen; after that, refreshing it is cheap.
        if (box == null || !box.refresh()) {
            AccessibilityNodeInfo root = ua.getRootInActiveWindow();
            box = root == null ? null : findEditText(root);
        }
        if (box == null) return null;
        if (box.isShowingHintText() || box.getText() == null) return "";
        return box.getText().toString();
    }

    private static AccessibilityNodeInfo findEditText(AccessibilityNodeInfo n) {
        if (n.getClassName() != null && n.getClassName().toString().endsWith("EditText")) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo found = findEditText(c);
            if (found != null) return found;
        }
        return null;
    }

    private static void tap(UiAutomation ua, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        ua.injectInputEvent(ev, true);
        ev.recycle();
        ev = MotionEvent.obtain(down, SystemClock.uptimeMillis() + 50, MotionEvent.ACTION_UP, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        ua.injectInputEvent(ev, true);
        ev.recycle();
    }

    private static void key(UiAutomation ua, int code) {
        long now = SystemClock.uptimeMillis();
        ua.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), true);
        ua.injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), true);
    }
}
