package com.phonebridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Minimal status screen: server URL + token, start/stop, accessibility state.
 */
public class MainActivity extends Activity {

    private TextView statusText;
    private TextView a11yText;
    private EditText editUrl;
    private EditText editToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        a11yText = findViewById(R.id.a11y_text);
        editUrl = findViewById(R.id.edit_url);
        editToken = findViewById(R.id.edit_token);

        SharedPreferences p = getSharedPreferences("pb", MODE_PRIVATE);
        editUrl.setText(p.getString("url", ""));
        editToken.setText(p.getString("token", ""));

        Button start = findViewById(R.id.btn_start);
        Button stop = findViewById(R.id.btn_stop);
        Button a11y = findViewById(R.id.btn_a11y);
        Button refresh = findViewById(R.id.btn_refresh);

        start.setOnClickListener(v -> {
            String url = editUrl.getText().toString().trim();
            String token = editToken.getText().toString().trim();
            if (url.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Enter server URL and token first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                Toast.makeText(this, "URL must start with https:// (or http://)", Toast.LENGTH_SHORT).show();
                return;
            }
            p.edit().putString("url", url).putString("token", token).apply();
            Intent i = new Intent(this, PollService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Toast.makeText(this, "Bridge started", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        stop.setOnClickListener(v -> {
            stopService(new Intent(this, PollService.class));
            PollService.status = "stopped";
            refreshStatus();
        });

        a11y.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        refresh.setOnClickListener(v -> refreshStatus());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        statusText.setText("Status: " + PollService.status);
        boolean on = isAccessibilityServiceEnabled();
        a11yText.setText("Accessibility service: " + (on ? "ON" : "OFF"));
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String me = getPackageName() + "/" + BridgeService.class.getName();
        return enabled.contains(me);
    }
}
