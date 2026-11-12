package com.agy.ui.webview;

import android.util.Log;
import android.webkit.WebView;

/** WebView resume/timer/memory lifecycle, kept out of the activity. */
public final class WebViewLifecycle {

    private static final String TAG = "WebViewHost";
    private static volatile long lastPauseAtMs = 0L;

    private WebViewLifecycle() {
    }

    /** Resume the page and tell it the host is back in front. */
    public static void resume(WebView webView) {
        if (webView == null) {
            return;
        }
        long awayMs = lastPauseAtMs > 0L ? Math.max(0L, System.currentTimeMillis() - lastPauseAtMs) : 0L;
        lastPauseAtMs = 0L;
        try {
            webView.onResume();
            webView.resumeTimers();
            webView.evaluateJavascript(
                    "(function(){ try {"
                    + " window.dispatchEvent(new CustomEvent('agy:host-resume', { detail: { awayMs: " + awayMs + " } }));"
                    + " window.dispatchEvent(new Event('focus'));"
                    + "} catch(e){} })();",
                    null);
        } catch (Exception e) {
            Log.w(TAG, "failed to resume webView", e);
        }
    }

    /**
     * Marks background transition without pausing WebView timers or JS execution,
     * so background agent streams and reconnect loops stay alive.
     */
    public static void pause(WebView webView) {
        if (webView == null) {
            return;
        }
        lastPauseAtMs = System.currentTimeMillis();
        try {
            webView.evaluateJavascript(
                    "(function(){ try { window.dispatchEvent(new CustomEvent('agy:host-pause')); } catch(e){} })();",
                    null);
        } catch (Exception e) {
            Log.w(TAG, "failed to notify webView pause", e);
        }
    }

    public static void freeMemory(WebView webView) {
        if (webView == null) {
            return;
        }
        try {
            webView.freeMemory();
        } catch (Exception e) {
            Log.w(TAG, "freeMemory failed", e);
        }
    }
}
