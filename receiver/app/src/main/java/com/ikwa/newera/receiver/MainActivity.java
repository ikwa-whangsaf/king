package com.ikwa.newera.receiver;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ADMIN = 1001;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private EditText etServerUrl;
    private TextView tvStatus, tvLog, tvAdminStatus;
    private Button btnConnect, btnAdmin;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);
        prefs = getSharedPreferences("ikwanewera", MODE_PRIVATE);

        etServerUrl = findViewById(R.id.etServerUrl);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvAdminStatus = findViewById(R.id.tvAdminStatus);
        btnConnect = findViewById(R.id.btnConnect);
        btnAdmin = findViewById(R.id.btnAdmin);

        // Load saved URL
        String savedUrl = prefs.getString("server_url", "ws://192.168.1.1:4101");
        etServerUrl.setText(savedUrl);

        btnConnect.setOnClickListener(v -> toggleService());
        btnAdmin.setOnClickListener(v -> requestAdmin());

        updateAdminStatus();

        // Auto-start service if was connected before
        if (prefs.getBoolean("auto_connect", false)) {
            startRemoteService();
        }

        // Register log receiver
        RemoteService.setLogListener(log -> runOnUiThread(() -> {
            tvLog.setText(log + "\n" + tvLog.getText());
        }));

        RemoteService.setStatusListener(connected -> runOnUiThread(() -> {
            if (connected) {
                tvStatus.setText("● ONLINE");
                tvStatus.setTextColor(0xFF9B59B6);
                btnConnect.setText("DISCONNECT");
            } else {
                tvStatus.setText("● OFFLINE");
                tvStatus.setTextColor(0xFF666666);
                btnConnect.setText("CONNECT");
            }
        }));
    }

    private void toggleService() {
        String url = etServerUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Masukkan URL server", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString("server_url", url).apply();

        if (RemoteService.isConnected()) {
            stopRemoteService();
        } else {
            startRemoteService();
        }
    }

    private void startRemoteService() {
        String url = etServerUrl.getText().toString().trim();
        Intent intent = new Intent(this, RemoteService.class);
        intent.putExtra("server_url", url);
        startForegroundService(intent);
        prefs.edit().putBoolean("auto_connect", true).apply();
    }

    private void stopRemoteService() {
        Intent intent = new Intent(this, RemoteService.class);
        stopService(intent);
        prefs.edit().putBoolean("auto_connect", false).apply();
    }

    private void requestAdmin() {
        if (dpm.isAdminActive(adminComponent)) {
            Toast.makeText(this, "Device Admin sudah aktif ✓", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Diperlukan untuk mematikan layar dari jarak jauh");
        startActivityForResult(intent, REQUEST_ADMIN);
    }

    private void updateAdminStatus() {
        if (dpm.isAdminActive(adminComponent)) {
            tvAdminStatus.setText("Device Admin: AKTIF ✓");
            tvAdminStatus.setTextColor(0xFF9B59B6);
            btnAdmin.setText("ADMIN AKTIF");
        } else {
            tvAdminStatus.setText("Device Admin: NONAKTIF");
            tvAdminStatus.setTextColor(0xFFFF3355);
            btnAdmin.setText("AKTIFKAN ADMIN");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ADMIN) {
            updateAdminStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAdminStatus();
        if (RemoteService.isConnected()) {
            tvStatus.setText("● ONLINE");
            tvStatus.setTextColor(0xFF9B59B6);
            btnConnect.setText("DISCONNECT");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RemoteService.setLogListener(null);
        RemoteService.setStatusListener(null);
    }
}
