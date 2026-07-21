package com.acde

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbWakeReceiver"
        const val ACTION_USB_PERMISSION = "com.acde.USB_PERMISSION"

        // Prevent duplicate permission dialogs during dongle power-on
        @Volatile private var permissionRequested = false
        @Volatile private var lastGrantMs = 0L
        @Volatile private var wakeScheduled = false

        @JvmStatic fun isPermissionPending(): Boolean = permissionRequested
    }

    override fun onReceive(context: Context, intent: Intent) {
        val device = extractDevice(intent) ?: return
        if (!DeviceRegistry.isSupportedDevice(device)) return

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return

        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                permissionRequested = false
                if (granted) lastGrantMs = System.currentTimeMillis()
                Log.i(TAG, "Permission: granted=$granted for ${device.deviceName}")
                if (granted && !wakeScheduled) {
                    wakeScheduled = true
                    val pendingResult = goAsync()
                    DongleWaker.wake(device, usbManager,
                        onLog = { l, m -> Log.i(TAG, "[$l] $m") },
                        onComplete = { wakeScheduled = false; pendingResult.finish() },
                    )
                }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val deviceName = device.deviceName
                Log.i(TAG, "Attached: $deviceName hasPerm=${usbManager.hasPermission(device)}")

                // Suppress dialogs for 5s after a grant — the dongle reconnects
                // after permission is granted and would trigger a second dialog.
                if (System.currentTimeMillis() - lastGrantMs < 5000) {
                    Log.i(TAG, "Post-grant cooldown — skipping $deviceName")
                    return
                }

                if (!usbManager.hasPermission(device)) {
                    if (permissionRequested) {
                        Log.i(TAG, "Permission already requested — skipping duplicate dialog")
                        return
                    }
                    permissionRequested = true
                    val pi = PendingIntent.getBroadcast(
                        context, 0,
                        Intent(ACTION_USB_PERMISSION).apply {
                            setPackage(context.packageName)
                        },
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    usbManager.requestPermission(device, pi)
                } else {
                    if (!wakeScheduled) {
                        wakeScheduled = true
                        val pendingResult = goAsync()
                        DongleWaker.wake(device, usbManager,
                            onLog = { l, m -> Log.i(TAG, "[$l] $m") },
                            onComplete = { wakeScheduled = false; pendingResult.finish() },
                        )
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
