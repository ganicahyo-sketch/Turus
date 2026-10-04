package id.turus.stasiuncuaca;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

public final class PendingUsbPermission {
    private PendingUsbPermission() {}

    public static void request(Activity activity, UsbManager manager, UsbDevice device, int requestCode, Runnable onResult) {
        String action = activity.getPackageName() + ".USB_PERMISSION";
        Intent in = new Intent(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(activity, requestCode, in, flags);
        activity.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try { context.unregisterReceiver(this); } catch (Exception ignored) {}
                if (onResult != null) activity.runOnUiThread(onResult);
            }
        }, new android.content.IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        manager.requestPermission(device, pi);
    }

    public static void request(Activity activity, UsbManager manager, UsbDevice device, int requestCode) {
        request(activity, manager, device, requestCode, null);
    }
}
