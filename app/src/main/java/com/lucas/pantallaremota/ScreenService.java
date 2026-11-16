package com.lucas.pantallaremota;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Captura la pantalla, la sirve por WebSocket y recibe las órdenes de control. */
public class ScreenService extends Service {

    static final String ACTION_STOP = "com.lucas.pantallaremota.STOP";
    static final int PORT = 8080;
    private static final String CHANNEL = "pantalla_remota";
    private static final int MAX_SIDE = 1080;   // lado mayor de la imagen enviada

    static volatile ScreenService instance;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private ScheduledExecutorService encoder;
    private RemoteServer server;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;

    // Tamaño real de la pantalla (para mapear los toques) y de la captura.
    volatile int realW, realH;
    private int capW, capH;

    // Último cuadro capturado (se reutiliza para no crear objetos en cada frame).
    private final Object frameLock = new Object();
    private Bitmap latest;
    private boolean dirty;
    private volatile byte[] lastJpeg;
    volatile int jpegQuality = 55;
    volatile int frameIntervalMs = 80;   // ~12 fps
    private long lastEncode;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (intent == null || projection != null) return START_NOT_STICKY;

        int code = intent.getIntExtra("code", 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("data", Intent.class)
                : intent.getParcelableExtra("data");
        if (data == null) { stopSelf(); return START_NOT_STICKY; }

        thread = new HandlerThread("captura");
        thread.start();
        handler = new Handler(thread.getLooper());

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, handler);

        acquireLocks();
        setupDisplay();

        encoder = Executors.newSingleThreadScheduledExecutor();
        encoder.scheduleWithFixedDelay(this::encodeTick, 100, 20, TimeUnit.MILLISECONDS);

        try {
            server = new RemoteServer(PORT, this);
            server.start(0, false);  // sin timeout de lectura: el WebSocket queda abierto
        } catch (Exception e) {
            server = null;
        }
        instance = this;
        return START_NOT_STICKY;
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
                .setContentText("Compartiendo pantalla en el puerto " + PORT)
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
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pantallaremota:cpu");
        cpuLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pantallaremota:wifi");
            wifiLock.acquire();
        }
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    /** Crea (o adapta tras una rotación) la pantalla virtual que espeja la real. */
    private void setupDisplay() {
        DisplayMetrics dm = realMetrics();
        realW = dm.widthPixels;
        realH = dm.heightPixels;
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(realW, realH));
        capW = Math.max(2, Math.round(realW * scale) & ~1);
        capH = Math.max(2, Math.round(realH * scale) & ~1);

        ImageReader old = reader;
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);

        if (virtualDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay("pantalla-remota", capW, capH,
                    dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } else {
            virtualDisplay.resize(capW, capH, dm.densityDpi);
            virtualDisplay.setSurface(reader.getSurface());
        }
        if (old != null) old.close();
        if (server != null) server.broadcastText(sizeMessage());
    }

    String sizeMessage() {
        return "{\"t\":\"size\",\"w\":" + capW + ",\"h\":" + capH + "}";
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (handler == null) return;
        handler.postDelayed(() -> {
            if (projection == null) return;
            DisplayMetrics dm = realMetrics();
            if (dm.widthPixels != realW || dm.heightPixels != realH) setupDisplay();
        }, 300);
    }

    /** Copia cada cuadro nuevo al bitmap "latest" (en el hilo de captura). */
    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (Exception e) { return; }
        if (img == null) return;
        try {
            if (r != reader) return; // cuadro de un reader viejo (rotación)
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int stridePx = plane.getRowStride() / pixelStride;
            int w = img.getWidth(), h = img.getHeight();
            synchronized (frameLock) {
                if (latest == null || latest.getWidth() != stridePx || latest.getHeight() != h) {
                    if (latest != null) latest.recycle();
                    latest = Bitmap.createBitmap(stridePx, h, Bitmap.Config.ARGB_8888);
                }
                buf.rewind();
                latest.copyPixelsFromBuffer(buf);
                latestW = w;
                dirty = true;
            }
        } catch (Exception ignored) {
        } finally {
            img.close();
        }
    }

    private int latestW;

    /** Comprime el último cuadro a JPEG y lo envía a los clientes conectados. */
    private void encodeTick() {
        try {
            if (server == null || !server.hasClients()) return;
            long now = System.currentTimeMillis();
            if (now - lastEncode < frameIntervalMs) return;
            byte[] jpg;
            synchronized (frameLock) {
                if (!dirty || latest == null) return;
                dirty = false;
                Bitmap src = latest;
                Bitmap crop = latestW < src.getWidth()
                        ? Bitmap.createBitmap(src, 0, 0, latestW, src.getHeight())
                        : src;
                ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);
                crop.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
                if (crop != src) crop.recycle();
                jpg = out.toByteArray();
            }
            lastEncode = now;
            lastJpeg = jpg;
            server.broadcast(jpg);
        } catch (Throwable ignored) { }
    }

    byte[] lastFrame() { return lastJpeg; }

    /** Pide un cuadro nuevo aunque la pantalla no haya cambiado (al conectarse alguien). */
    void requestFrame() {
        synchronized (frameLock) { if (latest != null) dirty = true; }
    }

    // ---------------------------------------------------------------- órdenes

    /** Ejecuta una orden recibida del navegador. Devuelve un mensaje para el cliente o null. */
    String handleCommand(JSONObject o) {
        String t = o.optString("t");
        switch (t) {
            case "wake":
                wakeScreen();
                return null;
            case "quality":
                jpegQuality = clamp(o.optInt("v", 55), 15, 90);
                frameIntervalMs = clamp(o.optInt("ms", 80), 40, 500);
                requestFrame();
                return null;
            case "ping":
                return "{\"t\":\"pong\"}";
        }

        ControlService c = ControlService.instance;
        if (c == null) return "{\"t\":\"noacc\"}";
        float w = realW, h = realH;
        switch (t) {
            case "tap":
                c.tap((float) o.optDouble("x") * w, (float) o.optDouble("y") * h, o.optLong("d", 40));
                break;
            case "swipe":
                c.swipe((float) o.optDouble("x1") * w, (float) o.optDouble("y1") * h,
                        (float) o.optDouble("x2") * w, (float) o.optDouble("y2") * h,
                        o.optLong("d", 300));
                break;
            case "key":
                c.globalKey(o.optString("k"));
                break;
            case "text":
                if (!c.typeText(o.optString("v"))) return "{\"t\":\"nofield\"}";
                break;
            case "bksp":
                if (!c.backspace()) return "{\"t\":\"nofield\"}";
                break;
            case "clear":
                if (!c.clearText()) return "{\"t\":\"nofield\"}";
                break;
            case "enter":
                c.enter();
                break;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "pantallaremota:wake");
        wl.acquire(5000);
        requestFrame();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override
    public void onDestroy() {
        instance = null;
        if (server != null) { try { server.stop(); } catch (Exception ignored) { } server = null; }
        if (encoder != null) encoder.shutdownNow();
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            try { p.stop(); } catch (Exception ignored) { }
        }
        if (thread != null) thread.quitSafely();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }
}
