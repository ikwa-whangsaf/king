package com.ikwa.newera.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class RemoteService extends Service {

    private static final String CHANNEL_ID = "ikwanewera_receiver";
    private static final int NOTIF_ID = 1;

    private static boolean connected = false;
    private static LogListener logListener;
    private static StatusListener statusListener;

    private WebSocket webSocket;
    private OkHttpClient client;
    private PowerManager.WakeLock wakeLock;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private CameraManager cameraManager;
    private String torchCameraId = null;
    private boolean torchOn = false;
    private String serverUrl;

    // Reconnect
    private boolean shouldRun = true;
    private int reconnectDelay = 3000;

    public interface LogListener { void onLog(String log); }
    public interface StatusListener { void onStatus(boolean connected); }

    public static void setLogListener(LogListener l) { logListener = l; }
    public static void setStatusListener(StatusListener l) { statusListener = l; }
    public static boolean isConnected() { return connected; }

    @Override
    public void onCreate() {
        super.onCreate();
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            serverUrl = intent.getStringExtra("server_url");
        }
        startForeground(NOTIF_ID, buildNotification("Menghubungkan..."));
        shouldRun = true;
        new Thread(this::connectLoop).start();
        return START_STICKY;
    }

    private void connectLoop() {
        while (shouldRun) {
            try {
                addLog("Connecting ke " + serverUrl);
                client = new OkHttpClient.Builder()
                        .pingInterval(20, TimeUnit.SECONDS)
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .build();

                Request request = new Request.Builder().url(serverUrl).build();
                webSocket = client.newWebSocket(request, new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response response) {
                        connected = true;
                        reconnectDelay = 3000;
                        addLog("Terhubung ke server ✓");
                        updateNotification("ONLINE — siap dikontrol");
                        if (statusListener != null) statusListener.onStatus(true);
                        sendJson(ws, "{\"type\":\"register\",\"role\":\"receiver\"}");
                        acquireWakeLock();
                    }

                    @Override
                    public void onMessage(WebSocket ws, String text) {
                        handleMessage(ws, text);
                    }

                    @Override
                    public void onFailure(WebSocket ws, Throwable t, Response response) {
                        connected = false;
                        addLog("Gagal: " + t.getMessage());
                        if (statusListener != null) statusListener.onStatus(false);
                        updateNotification("Reconnecting...");
                        releaseWakeLock();
                    }

                    @Override
                    public void onClosed(WebSocket ws, int code, String reason) {
                        connected = false;
                        addLog("Terputus: " + reason);
                        if (statusListener != null) statusListener.onStatus(false);
                        updateNotification("Terputus");
                        releaseWakeLock();
                    }
                });

                // Tunggu sampai disconnect
                synchronized (this) {
                    while (connected && shouldRun) {
                        wait(1000);
                    }
                }

            } catch (Exception e) {
                addLog("Error: " + e.getMessage());
            }

            if (shouldRun) {
                addLog("Reconnect dalam " + (reconnectDelay / 1000) + "s...");
                try { Thread.sleep(reconnectDelay); } catch (InterruptedException ignored) {}
                reconnectDelay = Math.min(reconnectDelay * 2, 30000);
            }
        }
    }

    private void handleMessage(WebSocket ws, String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.getString("type");

            if (type.equals("heartbeat")) {
                sendJson(ws, "{\"type\":\"heartbeat_ack\"}");
                return;
            }

            if (!type.equals("command")) return;

            String action = msg.getString("action");
            addLog("Perintah: " + action);

            switch (action) {
                case "torch":
                    String facing = msg.optString("facing", "back");
                    boolean state = msg.optBoolean("state", true);
                    handleTorch(ws, facing, state);
                    break;
                case "torchOff":
                    turnOffTorch(ws);
                    break;
                case "vibrate":
                    doVibrate(ws);
                    break;
                case "screenOff":
                    doScreenOff(ws);
                    break;
                case "keepAwake":
                    boolean on = msg.optBoolean("state", true);
                    if (on) acquireWakeLock(); else releaseWakeLock();
                    sendResponse(ws, "Keep Awake " + (on ? "ON" : "OFF"));
                    break;
                case "getStatus":
                    sendStatus(ws);
                    break;
            }
        } catch (Exception e) {
            addLog("Parse error: " + e.getMessage());
        }
    }

    private void handleTorch(WebSocket ws, String facing, boolean state) {
        try {
            if (!state) { turnOffTorch(ws); return; }
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                android.hardware.camera2.CameraCharacteristics chars =
                        cameraManager.getCameraCharacteristics(id);
                Integer lensFacing = chars.get(
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                boolean isBack = lensFacing != null &&
                        lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK;
                boolean isFront = lensFacing != null &&
                        lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;

                if ((facing.equals("back") && isBack) || (facing.equals("front") && isFront)) {
                    Boolean hasFlash = chars.get(
                            android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (hasFlash != null && hasFlash) {
                        cameraManager.setTorchMode(id, true);
                        torchCameraId = id;
                        torchOn = true;
                        addLog("Senter " + facing + " ON");
                        sendResponse(ws, "Senter " + facing + " ON");
                        return;
                    }
                }
            }
            sendResponse(ws, "Flash tidak tersedia di kamera " + facing);
        } catch (CameraAccessException e) {
            addLog("Torch error: " + e.getMessage());
            sendResponse(ws, "Torch error: " + e.getMessage());
        }
    }

    private void turnOffTorch(WebSocket ws) {
        try {
            if (torchCameraId != null) {
                cameraManager.setTorchMode(torchCameraId, false);
                torchCameraId = null;
                torchOn = false;
            }
            addLog("Senter OFF");
            sendResponse(ws, "Senter OFF");
        } catch (CameraAccessException e) {
            addLog("Torch off error: " + e.getMessage());
        }
    }

    private void doVibrate(WebSocket ws) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(
                    new long[]{0, 200, 100, 200, 100, 200}, -1));
            addLog("Vibrasi OK");
            sendResponse(ws, "Vibrasi OK");
        } else {
            sendResponse(ws, "Vibrator tidak tersedia");
        }
    }

    private void doScreenOff(WebSocket ws) {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow();
            addLog("Layar mati ✓");
            sendResponse(ws, "Layar mati");
        } else {
            addLog("Device Admin tidak aktif!");
            sendResponse(ws, "Gagal: aktifkan Device Admin dulu");
        }
    }

    private void sendStatus(WebSocket ws) {
        try {
            JSONObject data = new JSONObject();
            data.put("platform", "Android " + Build.VERSION.RELEASE);
            data.put("model", Build.MODEL);
            data.put("torch", torchOn ? "ON" : "OFF");
            data.put("adminActive", dpm.isAdminActive(adminComponent));
            JSONObject resp = new JSONObject();
            resp.put("type", "status");
            resp.put("data", data);
            sendJson(ws, resp.toString());
        } catch (Exception e) {
            addLog("Status error: " + e.getMessage());
        }
    }

    private void sendResponse(WebSocket ws, String msg) {
        try {
            JSONObject j = new JSONObject();
            j.put("type", "response");
            j.put("msg", msg);
            sendJson(ws, j.toString());
        } catch (Exception ignored) {}
    }

    private void sendJson(WebSocket ws, String json) {
        if (ws != null) ws.send(json);
    }

    private void acquireWakeLock() {
        if (wakeLock == null || !wakeLock.isHeld()) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "IkwaNewera:ReceiverLock");
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void addLog(String msg) {
        android.util.Log.d("IkwaNewera", msg);
        if (logListener != null) logListener.onLog(msg);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "IkwaNewera Receiver",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Status koneksi receiver");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("IkwaNewera Receiver")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shouldRun = false;
        connected = false;
        if (webSocket != null) webSocket.close(1000, "Service stopped");
        if (client != null) client.dispatcher().executorService().shutdown();
        releaseWakeLock();
        if (statusListener != null) statusListener.onStatus(false);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
