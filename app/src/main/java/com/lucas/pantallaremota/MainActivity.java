package com.lucas.pantallaremota;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 10;
    private TextView status, accStatus, pinView;
    private Button startBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ PIN

    static String getPin(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("cfg", MODE_PRIVATE);
        String pin = sp.getString("pin", null);
        if (pin == null) {
            pin = randomPin();
            sp.edit().putString("pin", pin).apply();
        }
        return pin;
    }

    private static String randomPin() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    // ------------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(18);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Pantalla Remota");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Manejá este celular desde Chrome en otro dispositivo.\n"
                + "Paso 1: activá el control (una sola vez).\nPaso 2: iniciá el compartir pantalla.");
        help.setPadding(0, dp(6), 0, dp(14));
        root.addView(help);

        accStatus = new TextView(this);
        root.addView(accStatus);
        Button accBtn = button("1. Activar control (Accesibilidad)");
        accBtn.setOnClickListener(v -> {
            Toast.makeText(this, "Buscá \"Pantalla Remota (control)\" y activalo", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        root.addView(accBtn);

        startBtn = button("2. Iniciar compartir pantalla");
        startBtn.setOnClickListener(v -> toggle());
        root.addView(startBtn);

        pinView = new TextView(this);
        pinView.setTextSize(22);
        pinView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinView.setPadding(0, dp(16), 0, 0);
        root.addView(pinView);

        Button pinBtn = button("Cambiar PIN");
        pinBtn.setOnClickListener(v -> changePin());
        root.addView(pinBtn);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(16), 0, dp(8));
        status.setTextIsSelectable(true);
        root.addView(status);

        Button copy = button("Copiar dirección");
        copy.setOnClickListener(v -> {
            List<String> urls = urls();
            if (urls.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("url", urls.get(0)));
            Toast.makeText(this, "Copiada: " + urls.get(0), Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);

        Button batt = button("Evitar que Android corte la app (batería)");
        batt.setOnClickListener(v -> askBattery());
        root.addView(batt);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean acc = ControlService.instance != null;
        accStatus.setText(acc ? "✔ Control activado" : "✖ Control desactivado (solo vas a poder ver, no tocar)");
        accStatus.setTextColor(acc ? Color.rgb(30, 140, 60) : Color.rgb(200, 60, 40));

        pinView.setText("PIN: " + getPin(this));

        boolean running = ScreenService.instance != null;
        startBtn.setText(running ? "Detener compartir pantalla" : "2. Iniciar compartir pantalla");
        if (running) {
            StringBuilder sb = new StringBuilder("🟢 Activo. Abrí en Chrome del otro celular:\n\n");
            List<String> urls = urls();
            if (urls.isEmpty()) sb.append("(no hay red: conectate a Wi-Fi)\n");
            for (String u : urls) sb.append(u).append('\n');
            sb.append("\nLas direcciones 100.x.x.x son de Tailscale (sirven desde cualquier lugar).");
            status.setText(sb.toString());
        } else {
            status.setText("⚪ Detenido");
        }
    }

    private void toggle() {
        if (ScreenService.instance != null) {
            startService(new Intent(this, ScreenService.class).setAction(ScreenService.ACTION_STOP));
            ui.postDelayed(this::refresh, 500);
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Permiso de captura cancelado", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, ScreenService.class)
                .putExtra("code", resultCode)
                .putExtra("data", data);
        startForegroundService(svc);
        ui.postDelayed(this::refresh, 800);
    }

    private void changePin() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("4 a 8 dígitos (vacío = aleatorio)");
        et.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this)
                .setTitle("Nuevo PIN")
                .setView(et)
                .setPositiveButton("Guardar", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) p = randomPin();
                    if (p.length() < 4 || p.length() > 8) {
                        Toast.makeText(this, "Usá entre 4 y 8 dígitos", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("pin", p).apply();
                    refresh();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void askBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Ya está excluida de la optimización", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    /** Direcciones http://IP:puerto de todas las redes activas (Wi-Fi, Tailscale, etc.). */
    private List<String> urls() {
        List<String> wifi = new ArrayList<>(), other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                // Datos móviles: esas IP no son alcanzables desde afuera, se omiten.
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String url = "http://" + a.getHostAddress() + ":" + ScreenService.PORT;
                    if (ni.getName().startsWith("wlan")) wifi.add(url); else other.add(url);
                }
            }
        } catch (Exception ignored) { }
        wifi.addAll(other);
        return wifi;
    }
}
