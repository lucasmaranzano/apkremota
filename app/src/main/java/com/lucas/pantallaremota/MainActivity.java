package com.lucas.pantallaremota;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
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

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 10;
    private TextView accStatus, codeView, status;
    private Button startBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1500); }
    };

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
        help.setText("Manejá este celular desde cualquier navegador (PC o celular), "
                + "desde cualquier lugar, con el link y el PIN.");
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

        codeView = new TextView(this);
        codeView.setTextSize(18);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setPadding(0, dp(18), 0, dp(4));
        codeView.setTextIsSelectable(true);
        root.addView(codeView);

        Button copy = button("Copiar link");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", Config.link(this)));
            Toast.makeText(this, "Link copiado", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);

        Button share = button("Enviar link (WhatsApp, mail…)");
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, Config.link(this));
            startActivity(Intent.createChooser(i, "Enviar link"));
        });
        root.addView(share);

        Button pinBtn = button("Cambiar PIN");
        pinBtn.setOnClickListener(v -> changePin());
        root.addView(pinBtn);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(16), 0, dp(8));
        root.addView(status);

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
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    private void refresh() {
        boolean acc = ControlService.instance != null;
        accStatus.setText(acc ? "✔ Control activado" : "✖ Control desactivado (solo vas a poder ver, no tocar)");
        accStatus.setTextColor(acc ? Color.rgb(30, 140, 60) : Color.rgb(200, 60, 40));

        codeView.setText("Link: " + Config.link(this) + "\nPIN: " + Config.pin(this));

        ScreenService s = ScreenService.instance;
        startBtn.setText(s != null ? "Detener compartir pantalla" : "2. Iniciar compartir pantalla");
        if (s != null) {
            int n = s.viewerCount();
            status.setText("🟢 Activo — servidor: " + ScreenService.signalState
                    + "\nConectados ahora: " + n);
        } else {
            status.setText("⚪ Detenido" + ("Detenido".equals(ScreenService.signalState)
                    ? "" : " — " + ScreenService.signalState));
        }
    }

    private void toggle() {
        if (ScreenService.instance != null) {
            startService(new Intent(this, ScreenService.class).setAction(ScreenService.ACTION_STOP));
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
        startForegroundService(new Intent(this, ScreenService.class).putExtra("data", data));
    }

    private void changePin() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("6 a 10 dígitos");
        et.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this)
                .setTitle("Nuevo PIN")
                .setView(et)
                .setPositiveButton("Guardar", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.length() < 6 || p.length() > 10) {
                        Toast.makeText(this, "Usá entre 6 y 10 dígitos", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Config.setPin(this, p);
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
}
