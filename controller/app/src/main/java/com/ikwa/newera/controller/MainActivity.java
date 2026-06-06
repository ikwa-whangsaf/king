package com.ikwa.newera.controller;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {

    private EditText etServerUrl;
    private TextView tvStatus, tvReceiverStatus, tvLog, tvBattery, tvModel;
    private Button btnConnect;
    private View controlsPanel, statusPanel;

    private WebSocket webSocket;
    private OkHttpClient client;
    private boolean connected = false;
    private boolean receiverOnline = false;
    private SharedPreferences prefs;

    // Toggle states
    private boolean torchBackOn = false;
    private boolean torchFrontOn = false;
    private boolean keepAwakeOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ikwanewera_ctrl", MODE_PRIVATE);

        etServerUrl = findViewById(R.id.etServerUrl);
        tvStatus = findViewById(R.id.tvStatus);
        tvReceiverStatus = findViewById(R.id.tvReceiverStatus);
        tvLog = findViewById(R.id.tvLog);
        tvBattery = findViewById(R.id.tvBattery);
        tvModel = findViewById(R.id.tvModel);
        btnConnect = findViewById(R.id.btnConnect);
        controlsPanel = findViewById(R.id.controlsPanel);
        statusPanel = findViewById(R.id.statusPanel);

        String savedUrl = prefs.getString("server_url", "ws://192.168.1.1:4101");
        etServerUrl.setText(savedUrl);

        btnConnect.setOnClickListener(v -> toggleConnect());

        // Control buttons
        findViewById(R.id.btnTorchBack).setOnClickListener(v -> toggleTorch("back"));
        findViewById(R.id.btnTorchFront).setOnClickListener(v -> toggleTorch("front"));
        findViewById(R.id.btnTorchOff).setOnClickListener(v -> sendCommand("torchOff", null));
        findViewById(R.id.btnVibrate).setOnClickListener(v -> sendCommand("vibrate", null));
        findViewById(R.id.btnScreenOff).setOnClickListener(v -> sendCommand("screenOff", null));
        findViewById(R.id.btnKeepAwake).setOnClickListener(v -> toggleKeepAwake());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> sendCommand("getStatus", null));

        setControlsEnabled(false);
    }

    private void toggleConnect() {
        if (connected) {
            disconnect();
        } else {
            String url = etServerUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "Masukkan URL server", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("server_url", url).apply();
            connect(url);
        }
    }

    private void connect(String url) {
        addLog("Connecting ke " + url + "...");
        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                runOnUiThread(() -> {
                    connected = true;
                    tvStatus.setText("● SERVER OK");
                    tvStatus.setTextColor(0xFF9B59B6);
                    btnConnect.setText("DISCONNECT");
                    addLog("Terhubung ke server ✓");
                });
                ws.send("{\"type\":\"register\",\"role\":\"controller\"}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                runOnUiThread(() -> handleMessage(text));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                runOnUiThread(() -> {
                    connected = false;
                    receiverOnline = false;
                    tvStatus.setText("● OFFLINE");
                    tvStatus.setTextColor(0xFF666666);
                    btnConnect.setText("CONNECT");
                    setControlsEnabled(false);
                    setReceiverOnline(false);
                    addLog("Gagal: " + t.getMessage());
                });
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                runOnUiThread(() -> {
                    connected = false;
                    receiverOnline = false;
                    tvStatus.setText("● OFFLINE");
                    tvStatus.setTextColor(0xFF666666);
                    btnConnect.setText("CONNECT");
                    setControlsEnabled(false);
                    setReceiverOnline(false);
                    addLog("Terputus");
                });
            }
        });
    }

    private void disconnect() {
        if (webSocket != null) webSocket.close(1000, "User disconnect");
        if (client != null) client.dispatcher().executorService().shutdown();
    }

    private void handleMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.getString("type");

            switch (type) {
                case "registered":
                    addLog("Terdaftar sebagai CONTROLLER ✓");
                    break;
                case "receiver_status":
                    setReceiverOnline(msg.getBoolean("online"));
                    break;
                case "response":
                    addLog("Target: " + msg.getString("msg"));
                    break;
                case "status":
                    updateStatus(msg.getJSONObject("data"));
                    break;
                case "error":
                    addLog("ERROR: " + msg.getString("msg"));
                    break;
            }
        } catch (JSONException e) {
            addLog("Parse error: " + e.getMessage());
        }
    }

    private void setReceiverOnline(boolean online) {
        receiverOnline = online;
        setControlsEnabled(online);
        tvReceiverStatus.setText(online ? "TARGET ONLINE ✓" : "TARGET OFFLINE");
        tvReceiverStatus.setTextColor(online ? 0xFF9B59B6 : 0xFFFF3355);
        if (online) {
            addLog("Target HP terhubung ✓");
            sendCommand("getStatus", null);
        } else {
            addLog("Target HP offline");
        }
    }

    private void setControlsEnabled(boolean enabled) {
        controlsPanel.setAlpha(enabled ? 1f : 0.3f);
        controlsPanel.setEnabled(enabled);
        for (int id : new int[]{
                R.id.btnTorchBack, R.id.btnTorchFront, R.id.btnTorchOff,
                R.id.btnVibrate, R.id.btnScreenOff, R.id.btnKeepAwake, R.id.btnRefresh}) {
            View v = findViewById(id);
            if (v != null) v.setEnabled(enabled);
        }
    }

    private void sendCommand(String action, JSONObject extra) {
        if (!connected) { addLog("Belum connect ke server"); return; }
        if (!receiverOnline) { addLog("Target HP offline"); return; }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "command");
            msg.put("action", action);
            if (extra != null) {
                extra.keys().forEachRemaining(key -> {
                    try { msg.put(key, extra.get(key)); } catch (JSONException ignored) {}
                });
            }
            webSocket.send(msg.toString());
            addLog("→ " + action);
        } catch (JSONException e) {
            addLog("Send error: " + e.getMessage());
        }
    }

    private void toggleTorch(String facing) {
        try {
            JSONObject extra = new JSONObject();
            extra.put("facing", facing);
            if (facing.equals("back")) {
                torchBackOn = !torchBackOn;
                if (torchBackOn) torchFrontOn = false;
                extra.put("state", torchBackOn);
                updateTorchUI();
            } else {
                torchFrontOn = !torchFrontOn;
                if (torchFrontOn) torchBackOn = false;
                extra.put("state", torchFrontOn);
                updateTorchUI();
            }
            sendCommand("torch", extra);
        } catch (JSONException ignored) {}
    }

    private void updateTorchUI() {
        Button btnBack = findViewById(R.id.btnTorchBack);
        Button btnFront = findViewById(R.id.btnTorchFront);
        btnBack.setText(torchBackOn ? "🔦 BLKNG ON" : "🔦 BELAKANG");
        btnBack.setTextColor(torchBackOn ? 0xFFFFCC00 : 0xFF9B9BB0);
        btnFront.setText(torchFrontOn ? "💡 DEPAN ON" : "💡 DEPAN");
        btnFront.setTextColor(torchFrontOn ? 0xFFFFCC00 : 0xFF9B9BB0);
    }

    private void toggleKeepAwake() {
        keepAwakeOn = !keepAwakeOn;
        try {
            JSONObject extra = new JSONObject();
            extra.put("state", keepAwakeOn);
            sendCommand("keepAwake", extra);
            Button btn = findViewById(R.id.btnKeepAwake);
            btn.setText(keepAwakeOn ? "👁 AWAKE ON" : "👁 KEEP AWAKE");
            btn.setTextColor(keepAwakeOn ? 0xFFBF5FFF : 0xFF9B9BB0);
        } catch (JSONException ignored) {}
    }

    private void updateStatus(JSONObject data) {
        try {
            if (data.has("platform")) tvModel.setText(data.getString("model"));
            if (data.has("platform")) {
                tvBattery.setText(data.getString("platform"));
            }
            addLog("Status diperbarui ✓");
        } catch (JSONException ignored) {}
    }

    private void addLog(String msg) {
        runOnUiThread(() -> {
            String current = tvLog.getText().toString();
            String newLog = msg + "\n" + current;
            if (newLog.length() > 2000) newLog = newLog.substring(0, 2000);
            tvLog.setText(newLog);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
