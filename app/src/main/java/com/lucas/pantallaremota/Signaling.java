package com.lucas.pantallaremota;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Conexión con el servidor PeerJS: recibe ofertas del navegador y le devuelve respuestas. */
class Signaling extends WebSocketListener {

    interface Listener {
        void onSignal(String type, String src, JSONObject payload);
        void onSignalState(String state);
    }

    private final ScreenService svc;
    private final Listener listener;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .build();
    private final Handler h = new Handler(Looper.getMainLooper());
    private WebSocket ws;
    private volatile boolean stopped;
    private int retryMs = 2000;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            WebSocket w = ws;
            if (w != null) w.send("{\"type\":\"HEARTBEAT\"}");
            if (!stopped) h.postDelayed(this, 5000);
        }
    };

    Signaling(ScreenService svc, Listener listener) {
        this.svc = svc;
        this.listener = listener;
    }

    void connect() {
        if (stopped) return;
        listener.onSignalState("Conectando al servidor…");
        String url = Config.SIGNAL_URL + "&id=" + Config.peerId(svc)
                + "&token=" + Config.token(svc) + "&version=1.5.4";
        ws = client.newWebSocket(new Request.Builder().url(url).build(), this);
    }

    void send(String type, String dst, JSONObject payload) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", type);
            m.put("dst", dst);
            m.put("payload", payload);
            WebSocket w = ws;
            if (w != null) w.send(m.toString());
        } catch (Exception ignored) { }
    }

    void stop() {
        stopped = true;
        h.removeCallbacksAndMessages(null);
        if (ws != null) ws.close(1000, "fin");
        client.dispatcher().executorService().shutdown();
    }

    private void retry() {
        h.removeCallbacks(heartbeat);
        if (stopped) return;
        listener.onSignalState("Sin conexión, reintentando…");
        h.postDelayed(this::connect, retryMs);
        retryMs = Math.min(retryMs * 2, 30000);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        h.removeCallbacks(heartbeat);
        h.postDelayed(heartbeat, 5000);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        JSONObject m;
        try { m = new JSONObject(text); } catch (Exception e) { return; }
        String type = m.optString("type");
        switch (type) {
            case "OPEN":
                retryMs = 2000;
                listener.onSignalState("En línea");
                break;
            case "ID-TAKEN":
                // Quedó una conexión vieja con otro token: se cambia el token y se reintenta.
                Config.newToken(svc);
                webSocket.close(1000, "id");
                break;
            case "ERROR":
                webSocket.close(1000, "error");
                break;
            case "OFFER":
            case "CANDIDATE":
            case "LEAVE":
            case "EXPIRE":
                JSONObject p = m.optJSONObject("payload");
                listener.onSignal(type, m.optString("src"), p == null ? new JSONObject() : p);
                break;
        }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        if (webSocket == ws) retry();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        if (webSocket == ws) retry();
    }
}
