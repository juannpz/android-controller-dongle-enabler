package com.acde

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Dispatches the correct wake strategy for a given USB device.
 * Lookup is done via [DeviceRegistry].
 */
object DongleWaker {

    fun wake(
        device: UsbDevice,
        usbManager: UsbManager,
        onLog: ((String, String) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val config = DeviceRegistry.getConfig(device.vendorId, device.productId)
        if (config != null) {
            config.waker(device, usbManager, onLog, onComplete)
        } else {
            onLog?.invoke("w", "No waker for VID:${device.vendorId} PID:${device.productId}")
            onComplete?.invoke()
        }
    }
}
