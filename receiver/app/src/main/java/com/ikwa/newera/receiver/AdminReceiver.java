package com.ikwa.newera.receiver;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        android.util.Log.d("IkwaNewera", "Device Admin diaktifkan");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        android.util.Log.d("IkwaNewera", "Device Admin dinonaktifkan");
    }
}
