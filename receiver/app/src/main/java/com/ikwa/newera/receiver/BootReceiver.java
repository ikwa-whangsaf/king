package com.ikwa.newera.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("ikwanewera", Context.MODE_PRIVATE);
            boolean autoConnect = prefs.getBoolean("auto_connect", false);
            String url = prefs.getString("server_url", "");
            if (autoConnect && !url.isEmpty()) {
                Intent service = new Intent(context, RemoteService.class);
                service.putExtra("server_url", url);
                context.startForegroundService(service);
            }
        }
    }
}
