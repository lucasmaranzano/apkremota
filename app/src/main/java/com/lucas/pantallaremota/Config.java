package com.lucas.pantallaremota;

import android.content.Context;
import android.content.SharedPreferences;

import org.webrtc.PeerConnection;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Configuración: página de control, servidor de señalización, código y PIN. */
final class Config {

    /** Página web desde donde se maneja el celular (se le agrega ?c=CODIGO). */
    static final String VIEWER_URL = "https://lucasmaranzano.github.io/apkremota/";

    /** Servidor público y gratuito de PeerJS: solo sirve para "presentar" a los dos equipos. */
    static final String SIGNAL_URL = "wss://0.peerjs.com:443/peerjs?key=peerjs";

    private static final String ALNUM = "abcdefghijkmnpqrstuvwxyz23456789";

    private Config() { }

    static List<PeerConnection.IceServer> iceServers() {
        List<PeerConnection.IceServer> list = new ArrayList<>();
        list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer());
        // Servidor de relevo público (para redes que no permiten conexión directa).
        List<String> turn = new ArrayList<>();
        turn.add("turn:openrelay.metered.ca:80");
        turn.add("turn:openrelay.metered.ca:443");
        turn.add("turn:openrelay.metered.ca:443?transport=tcp");
        list.add(PeerConnection.IceServer.builder(turn)
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer());
        return list;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static String random(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        return sb.toString();
    }

    /** Código fijo de este celular (también es su ID en el servidor de señalización). */
    static String code(Context c) {
        String v = sp(c).getString("code", null);
        if (v == null) { v = random(8); sp(c).edit().putString("code", v).apply(); }
        return v;
    }

    static String peerId(Context c) { return "pantallaremota-" + code(c); }

    static String token(Context c) {
        String v = sp(c).getString("token", null);
        if (v == null) { v = random(16); sp(c).edit().putString("token", v).apply(); }
        return v;
    }

    static void newToken(Context c) { sp(c).edit().putString("token", random(16)).apply(); }

    static String pin(Context c) {
        String v = sp(c).getString("pin", null);
        if (v == null) {
            v = String.format("%06d", new SecureRandom().nextInt(1_000_000));
            sp(c).edit().putString("pin", v).apply();
        }
        return v;
    }

    static void setPin(Context c, String pin) { sp(c).edit().putString("pin", pin).apply(); }

    static String link(Context c) { return VIEWER_URL + "?c=" + code(c); }
}
