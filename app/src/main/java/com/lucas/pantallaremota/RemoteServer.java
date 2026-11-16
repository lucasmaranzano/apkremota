package com.lucas.pantallaremota;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/** Servidor HTTP (sirve la página) + WebSocket (imagen y órdenes), protegido con PIN. */
public class RemoteServer extends NanoWSD {

    private final ScreenService svc;
    private final Set<Client> clients = new CopyOnWriteArraySet<>();
    private volatile long lastBadPin;

    RemoteServer(int port, ScreenService svc) {
        super(port);
        this.svc = svc;
    }

    @Override
    protected WebSocket openWebSocket(NanoHTTPD.IHTTPSession handshake) {
        return new Client(handshake);
    }

    @Override
    protected Response serveHttp(NanoHTTPD.IHTTPSession session) {
        String uri = session.getUri();
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            try (InputStream in = svc.getAssets().open("index.html")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                Response r = newFixedLengthResponse(Response.Status.OK,
                        "text/html; charset=utf-8", out.toString("UTF-8"));
                r.addHeader("Cache-Control", "no-store");
                return r;
            } catch (IOException e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No encontrado");
    }

    boolean hasClients() { return !clients.isEmpty(); }

    void broadcast(byte[] jpeg) {
        for (Client c : clients) c.sendSafe(jpeg);
    }

    void broadcastText(String msg) {
        for (Client c : clients) c.sendSafe(msg);
    }

    class Client extends WebSocket {
        private volatile boolean authed;

        Client(NanoHTTPD.IHTTPSession handshake) { super(handshake); }

        synchronized void sendSafe(byte[] data) {
            try { if (isOpen()) send(data); } catch (IOException e) { clients.remove(this); }
        }

        synchronized void sendSafe(String data) {
            try { if (isOpen()) send(data); } catch (IOException e) { clients.remove(this); }
        }

        @Override
        protected void onOpen() { }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean byRemote) {
            clients.remove(this);
        }

        @Override
        protected void onMessage(WebSocketFrame frame) {
            JSONObject o;
            try { o = new JSONObject(frame.getTextPayload()); } catch (Exception e) { return; }

            if (!authed) {
                if ("auth".equals(o.optString("t"))) {
                    // Freno contra intentos de adivinar el PIN.
                    if (System.currentTimeMillis() - lastBadPin < 1500) {
                        sendSafe("{\"t\":\"wait\"}");
                        return;
                    }
                    if (MainActivity.getPin(svc).equals(o.optString("pin"))) {
                        authed = true;
                        clients.add(this);
                        sendSafe("{\"t\":\"ok\"}");
                        sendSafe(svc.sizeMessage());
                        byte[] last = svc.lastFrame();
                        if (last != null) sendSafe(last);
                        svc.requestFrame();
                    } else {
                        lastBadPin = System.currentTimeMillis();
                        sendSafe("{\"t\":\"badpin\"}");
                    }
                }
                return;
            }

            String reply = svc.handleCommand(o);
            if (reply != null) sendSafe(reply);
        }

        @Override
        protected void onPong(WebSocketFrame pong) { }

        @Override
        protected void onException(IOException exception) {
            clients.remove(this);
        }
    }
}
