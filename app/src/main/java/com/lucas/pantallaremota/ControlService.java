package com.lucas.pantallaremota;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Servicio de accesibilidad: ejecuta toques, deslizamientos, botones y texto. */
public class ControlService extends AccessibilityService {

    static volatile ControlService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    void tap(float x, float y, long durationMs) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        dispatch(p, Math.max(1, durationMs));
    }

    void swipe(float x1, float y1, float x2, float y2, long durationMs) {
        Path p = new Path();
        p.moveTo(Math.max(0, x1), Math.max(0, y1));
        p.lineTo(Math.max(0, x2), Math.max(0, y2));
        dispatch(p, Math.max(50, Math.min(durationMs, 3000)));
    }

    private void dispatch(Path path, long duration) {
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        dispatchGesture(b.build(), null, null);
    }

    void globalKey(String key) {
        int action;
        switch (key) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            case "notif": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power": action = GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock":
                if (Build.VERSION.SDK_INT >= 28) { action = GLOBAL_ACTION_LOCK_SCREEN; break; }
                return;
            default: return;
        }
        performGlobalAction(action);
    }

    private AccessibilityNodeInfo focusedInput() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    }

    private String currentText(AccessibilityNodeInfo node) {
        if (node.isShowingHintText()) return "";
        CharSequence cs = node.getText();
        return cs == null ? "" : cs.toString();
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** Agrega texto al campo que tiene el foco. Devuelve false si no hay campo editable. */
    boolean typeText(String text) {
        AccessibilityNodeInfo node = focusedInput();
        if (node == null || !node.isEditable()) return false;
        return setText(node, currentText(node) + text);
    }

    boolean backspace() {
        AccessibilityNodeInfo node = focusedInput();
        if (node == null || !node.isEditable()) return false;
        String cur = currentText(node);
        if (cur.isEmpty()) return true;
        return setText(node, cur.substring(0, cur.length() - 1));
    }

    boolean clearText() {
        AccessibilityNodeInfo node = focusedInput();
        if (node == null || !node.isEditable()) return false;
        return setText(node, "");
    }

    boolean enter() {
        AccessibilityNodeInfo node = focusedInput();
        if (node == null) return false;
        if (Build.VERSION.SDK_INT >= 30) {
            return node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return typeText("\n");
    }
}
