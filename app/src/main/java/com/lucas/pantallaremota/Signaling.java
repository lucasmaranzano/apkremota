package com.lucas.pantallaremota;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Señalización por MQTT (servidor público, sin cuenta). Solo se usa para que el navegador
 * y el celular se "presenten"; el video y las órdenes viajan después directo y cifrados.
 *
 * Temas:  pantallaremota/CODIGO/in        → mensajes de los navegadores al celular
 *         pantallaremota/CODIGO/out/VISOR → respuestas del celular a cada navegador
 *         pantallaremota/CODIGO/status    → "online"/"offline" (retenido)
 */
class Signaling extends WebSocketListener {

    interface Listener {
        void onSignal(JSONObject msg);
        void onSignalState(String state);
    }

    private final ScreenService svc;
    private final Listener listener;
    private final String base;
    private final OkHttpClient client = new OkHttpClient.Builder().build();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private WebSocket ws;
    private volatile boolean stopped, connected;
    private int retryMs = 2000;

    private final Runnable ping = new Runnable() {
        @Override public void run() {
            WebSocket w = ws;
            if (w != null && connected) w.send(ByteString.of((byte) 0xC0, (byte) 0x00));
            if (!stopped) h.postDelayed(this, 30000);
        }
    };

    Signaling(ScreenService svc, Listener listener) {
        this.svc = svc;
        this.listener = listener;
        this.base = "pantallaremota/" + Config.code(svc);
    }

    void connect() {
        if (stopped) return;
        listener.onSignalState("Conectando al servidor…");
        synchronized (buf) { buf.reset(); }
        Request req = new Request.Builder().url(Config.SIGNAL_URL)
                .header("Sec-WebSocket-Protocol", "mqtt").build();
        ws = client.newWebSocket(req, this);
    }

    /** Envía un mensaje a un navegador concreto. */
    void sendTo(String viewer, JSONObject msg) {
        publish(base + "/out/" + viewer, msg.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    void stop() {
        stopped = true;
        h.removeCallbacksAndMessages(null);
        WebSocket w = ws;
        if (w != null) {
            if (connected) {
                publish(base + "/status", "offline".getBytes(StandardCharsets.UTF_8), true);
                w.send(ByteString.of((byte) 0xE0, (byte) 0x00)); // DISCONNECT
            }
            w.close(1000, "fin");
        }
        client.dispatcher().executorService().shutdown();
    }

    // ---------------------------------------------------------------- MQTT

    private static void putStr(ByteArrayOutputStream o, byte[] s) {
        o.write(s.length >> 8);
        o.write(s.length & 0xFF);
        o.write(s, 0, s.length);
    }

    private static byte[] packet(int header, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(body.length + 5);
        o.write(header);
        int n = body.length;
        do {
            int d = n % 128;
            n /= 128;
            if (n > 0) d |= 0x80;
            o.write(d);
        } while (n > 0);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private void publish(String topic, byte[] payload, boolean retain) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        putStr(b, topic.getBytes(StandardCharsets.UTF_8));
        b.write(payload, 0, payload.length);
        WebSocket w = ws;
        if (w != null) w.send(ByteString.of(packet(retain ? 0x31 : 0x30, b.toByteArray())));
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        putStr(b, "MQTT".getBytes(StandardCharsets.US_ASCII));
        b.write(4);                    // MQTT 3.1.1
        b.write(0x02 | 0x04 | 0x20);   // sesión limpia + mensaje de despedida retenido
        b.write(0);
        b.write(60);                   // keep alive 60 s
        putStr(b, ("pr-" + Config.code(svc) + "-" + Config.random(6)).getBytes(StandardCharsets.UTF_8));
        putStr(b, (base + "/status").getBytes(StandardCharsets.UTF_8));
        putStr(b, "offline".getBytes(StandardCharsets.UTF_8));
        webSocket.send(ByteString.of(packet(0x10, b.toByteArray())));
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        if (webSocket != ws) return;
        synchronized (buf) {
            byte[] in = bytes.toByteArray();
            buf.write(in, 0, in.length);
            byte[] all = buf.toByteArray();
            int pos = 0;
            while (true) {
                if (all.length - pos < 2) break;
                int i = pos + 1, mult = 1, len = 0, d;
                boolean complete = true;
                do {
                    if (i >= all.length) { complete = false; break; }
                    d = all[i++] & 0xFF;
                    len += (d & 0x7F) * mult;
                    mult *= 128;
                } while ((d & 0x80) != 0);
                if (!complete || all.length - i < len) break;
                handlePacket(all[pos] & 0xFF, all, i, len);
                pos = i + len;
            }
            buf.reset();
            buf.write(all, pos, all.length - pos);
        }
    }

    private void handlePacket(int header, byte[] a, int off, int len) {
        int type = header >> 4;
        if (type == 2) { // CONNACK
            if (len >= 2 && a[off + 1] == 0) {
                connected = true;
                retryMs = 2000;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                b.write(0);
                b.write(1);
                putStr(b, (base + "/in").getBytes(StandardCharsets.UTF_8));
                b.write(0);
                ws.send(ByteString.of(packet(0x82, b.toByteArray()))); // SUBSCRIBE
                publish(base + "/status", "online".getBytes(StandardCharsets.UTF_8), true);
                h.removeCallbacks(ping);
                h.postDelayed(ping, 30000);
                listener.onSignalState("En línea");
            } else {
                ws.close(1000, "rechazado");
            }
        } else if (type == 3) { // PUBLISH
            int tl = ((a[off] & 0xFF) << 8) | (a[off + 1] & 0xFF);
            int qos = (header >> 1) & 3;
            int p = off + 2 + tl + (qos > 0 ? 2 : 0);
            String payload = new String(a, p, off + len - p, StandardCharsets.UTF_8);
            try { listener.onSignal(new JSONObject(payload)); } catch (Exception ignored) { }
        }
    }

    private void retry() {
        connected = false;
        h.removeCallbacks(ping);
        if (stopped) return;
        listener.onSignalState("Sin conexión, reintentando…");
        h.postDelayed(this::connect, retryMs);
        retryMs = Math.min(retryMs * 2, 30000);
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
