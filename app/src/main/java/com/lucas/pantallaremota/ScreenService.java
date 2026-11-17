package com.lucas.pantallaremota;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Captura la pantalla y la transmite por WebRTC a los navegadores que se conectan
 * con el código y el PIN. Recibe las órdenes (toques, teclas) por un canal de datos.
 */
public class ScreenService extends Service implements Signaling.Listener {

    static final String ACTION_STOP = "com.lucas.pantallaremota.STOP";
    private static final String CHANNEL = "pantalla_remota";
    private static final int MAX_SIDE = 1280;
    private static final int FPS = 20;

    static volatile ScreenService instance;
    static volatile String signalState = "Detenido";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private EglBase egl;
    private PeerConnectionFactory factory;
    private ScreenCapturerAndroid capturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource source;
    private VideoTrack track;
    private Signaling signaling;
    private PowerManager.WakeLock cpuLock;

    private int capW, capH;
    private volatile long lastBadPin;
    private volatile int maxBitrate = 1_500_000;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (intent == null || factory != null) return START_NOT_STICKY;

        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("data", Intent.class)
                : intent.getParcelableExtra("data");
        if (data == null) { stopSelf(); return START_NOT_STICKY; }

        try {
            startCapture(data);
        } catch (Throwable t) {
            signalState = "Error al iniciar: " + t.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pantallaremota:cpu");
        cpuLock.acquire();

        signaling = new Signaling(this, this);
        signaling.connect();
        instance = this;
        return START_NOT_STICKY;
    }

    private void startCapture(Intent data) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(getApplicationContext()).createInitializationOptions());
        egl = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();

        capturer = new ScreenCapturerAndroid(data, new MediaProjection.Callback() {
            @Override public void onStop() { main.post(() -> stopSelf()); }
        });
        textureHelper = SurfaceTextureHelper.create("captura", egl.getEglBaseContext());
        source = factory.createVideoSource(true);
        capturer.initialize(textureHelper, getApplicationContext(), source.getCapturerObserver());

        // Tamaño fijo según la orientación natural; si el celular gira, Android
        // encuadra la imagen dentro de este marco (ver mapX/mapY).
        DisplayMetrics dm = realMetrics();
        int w = Math.min(dm.widthPixels, dm.heightPixels);
        int h = Math.max(dm.widthPixels, dm.heightPixels);
        float scale = Math.min(1f, MAX_SIDE / (float) h);
        capW = Math.max(16, Math.round(w * scale / 16f) * 16);
        capH = Math.max(16, Math.round(h * scale / 16f) * 16);
        capturer.startCapture(capW, capH, FPS);
        track = factory.createVideoTrack("pantalla", source);
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Pantalla Remota",
                NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, ScreenService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent openPi = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Pantalla Remota activa")
                .setContentText("Tu pantalla se puede ver con tu código y PIN")
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Detener", stopPi).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    int viewerCount() { return sessions.size(); }

    // ------------------------------------------------------------ señalización

    @Override
    public void onSignalState(String state) {
        signalState = state;
    }

    @Override
    public void onSignal(String type, String src, JSONObject payload) {
        exec.execute(() -> {
            try {
                switch (type) {
                    case "OFFER": handleOffer(src, payload); break;
                    case "CANDIDATE": {
                        Session s = sessions.get(payload.optString("sid"));
                        if (s != null) s.addRemoteCandidate(payload);
                        break;
                    }
                    case "LEAVE":
                    case "EXPIRE":
                        for (Session s : sessions.values()) if (s.peer.equals(src)) s.close();
                        break;
                }
            } catch (Throwable ignored) { }
        });
    }

    private void handleOffer(String src, JSONObject p) throws Exception {
        String sid = p.optString("sid");
        if (sid.isEmpty() || track == null) return;
        JSONObject reply = new JSONObject().put("sid", sid);

        if (System.currentTimeMillis() - lastBadPin < 2000) {
            signaling.send("ANSWER", src, reply.put("error", "wait"));
            return;
        }
        if (!Config.pin(this).equals(p.optString("pin"))) {
            lastBadPin = System.currentTimeMillis();
            signaling.send("ANSWER", src, reply.put("error", "badpin"));
            return;
        }

        Session old = sessions.remove(sid);
        if (old != null) old.close();
        Session s = new Session(src, sid);
        sessions.put(sid, s);
        s.start(p.optString("sdp"));
    }

    // ------------------------------------------------------------ órdenes

    /** Convierte una coordenada 0..1 del video a píxeles reales de la pantalla. */
    private float[] map(double fx, double fy) {
        DisplayMetrics dm = realMetrics();
        float rw = dm.widthPixels, rh = dm.heightPixels;
        float s = Math.min(capW / rw, capH / rh);
        float offX = (capW - rw * s) / 2f, offY = (capH - rh * s) / 2f;
        float x = (float) ((fx * capW - offX) / s);
        float y = (float) ((fy * capH - offY) / s);
        return new float[]{Math.max(0, Math.min(rw - 1, x)), Math.max(0, Math.min(rh - 1, y))};
    }

    private String handleCommand(JSONObject o) {
        String t = o.optString("t");
        switch (t) {
            case "wake": wakeScreen(); return null;
            case "quality": setBitrate(o.optInt("kbps", 1500) * 1000); return null;
            case "ping": return "{\"t\":\"pong\"}";
        }
        ControlService c = ControlService.instance;
        if (c == null) return "{\"t\":\"noacc\"}";
        switch (t) {
            case "tap": {
                float[] a = map(o.optDouble("x"), o.optDouble("y"));
                c.tap(a[0], a[1], o.optLong("d", 40));
                break;
            }
            case "swipe": {
                float[] a = map(o.optDouble("x1"), o.optDouble("y1"));
                float[] b = map(o.optDouble("x2"), o.optDouble("y2"));
                c.swipe(a[0], a[1], b[0], b[1], o.optLong("d", 300));
                break;
            }
            case "key": c.globalKey(o.optString("k")); break;
            case "text": if (!c.typeText(o.optString("v"))) return "{\"t\":\"nofield\"}"; break;
            case "bksp": if (!c.backspace()) return "{\"t\":\"nofield\"}"; break;
            case "clear": if (!c.clearText()) return "{\"t\":\"nofield\"}"; break;
            case "enter": c.enter(); break;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "pantallaremota:wake");
        wl.acquire(5000);
    }

    private void setBitrate(int bps) {
        maxBitrate = Math.max(150_000, Math.min(bps, 6_000_000));
        for (Session s : sessions.values()) s.applyBitrate();
    }

    @Override
    public void onDestroy() {
        instance = null;
        signalState = "Detenido";
        if (signaling != null) signaling.stop();
        for (Session s : sessions.values()) s.close();
        exec.shutdown();
        try { if (capturer != null) capturer.stopCapture(); } catch (Exception ignored) { }
        if (capturer != null) capturer.dispose();
        if (track != null) track.dispose();
        if (source != null) source.dispose();
        if (textureHelper != null) textureHelper.dispose();
        if (factory != null) factory.dispose();
        if (egl != null) egl.release();
        factory = null;
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------ sesión

    private static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription d) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String e) { }
        @Override public void onSetFailure(String e) { }
    }

    /** Una conexión con un navegador. */
    private class Session implements PeerConnection.Observer, DataChannel.Observer {
        final String peer, sid;
        PeerConnection pc;
        RtpSender sender;
        DataChannel dc;
        boolean remoteSet, closed;
        final List<IceCandidate> pending = new ArrayList<>();

        Session(String peer, String sid) { this.peer = peer; this.sid = sid; }

        void start(String offerSdp) {
            PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(Config.iceServers());
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            pc = factory.createPeerConnection(cfg, this);
            if (pc == null) { sessions.remove(sid); return; }
            pc.setRemoteDescription(new SdpAdapter() {
                @Override public void onSetSuccess() { exec.execute(Session.this::answer); }
                @Override public void onSetFailure(String e) { exec.execute(Session.this::close); }
            }, new SessionDescription(SessionDescription.Type.OFFER, offerSdp));
        }

        private void answer() {
            if (closed) return;
            remoteSet = true;
            for (IceCandidate c : pending) pc.addIceCandidate(c);
            pending.clear();
            sender = pc.addTrack(track, Collections.singletonList("pantalla"));
            applyBitrate();
            pc.createAnswer(new SdpAdapter() {
                @Override public void onCreateSuccess(SessionDescription d) {
                    pc.setLocalDescription(new SdpAdapter() {
                        @Override public void onSetSuccess() {
                            try {
                                signaling.send("ANSWER", peer, new JSONObject()
                                        .put("sid", sid).put("sdp", d.description));
                            } catch (Exception ignored) { }
                        }
                    }, d);
                }
                @Override public void onCreateFailure(String e) { exec.execute(Session.this::close); }
            }, new MediaConstraints());
        }

        void applyBitrate() {
            RtpSender s = sender;
            if (s == null) return;
            try {
                RtpParameters params = s.getParameters();
                for (RtpParameters.Encoding e : params.encodings) {
                    e.maxBitrateBps = maxBitrate;
                    e.maxFramerate = FPS;
                }
                s.setParameters(params);
            } catch (Exception ignored) { }
        }

        void addRemoteCandidate(JSONObject p) {
            IceCandidate c = new IceCandidate(p.optString("sdpMid"),
                    p.optInt("sdpMLineIndex"), p.optString("candidate"));
            if (remoteSet) pc.addIceCandidate(c); else pending.add(c);
        }

        void close() {
            if (closed) return;
            closed = true;
            sessions.remove(sid);
            try { if (dc != null) dc.close(); } catch (Exception ignored) { }
            try { if (pc != null) pc.close(); } catch (Exception ignored) { }
        }

        void sendText(String s) {
            DataChannel d = dc;
            if (d == null || d.state() != DataChannel.State.OPEN) return;
            d.send(new DataChannel.Buffer(ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)), false));
        }

        // PeerConnection.Observer
        @Override public void onIceCandidate(IceCandidate c) {
            try {
                signaling.send("CANDIDATE", peer, new JSONObject().put("sid", sid)
                        .put("candidate", c.sdp).put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex));
            } catch (Exception ignored) { }
        }
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState st) {
            if (st == PeerConnection.PeerConnectionState.FAILED
                    || st == PeerConnection.PeerConnectionState.CLOSED) {
                exec.execute(this::close);
            } else if (st == PeerConnection.PeerConnectionState.DISCONNECTED) {
                main.postDelayed(() -> {
                    if (pc != null && !closed && pc.connectionState()
                            == PeerConnection.PeerConnectionState.DISCONNECTED) exec.execute(this::close);
                }, 15000);
            }
        }
        @Override public void onDataChannel(DataChannel channel) {
            dc = channel;
            channel.registerObserver(this);
        }
        @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
        @Override public void onIceConnectionReceivingChange(boolean b) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] c) { }
        @Override public void onAddStream(MediaStream s) { }
        @Override public void onRemoveStream(MediaStream s) { }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) { }

        // DataChannel.Observer
        @Override public void onBufferedAmountChange(long l) { }
        @Override public void onStateChange() { }
        @Override public void onMessage(DataChannel.Buffer buffer) {
            ByteBuffer data = buffer.data;
            byte[] b = new byte[data.remaining()];
            data.get(b);
            String txt = new String(b, StandardCharsets.UTF_8);
            exec.execute(() -> {
                try {
                    String reply = handleCommand(new JSONObject(txt));
                    if (reply != null) sendText(reply);
                } catch (Throwable ignored) { }
            });
        }
    }
}
