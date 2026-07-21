package com.acde

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Registry of supported USB devices and their wake strategies.
 *
 * To add a new device:
 * 1. Add a DeviceConfig entry below with the VID, PID, and name.
 * 2. Create a waker object following the same signature as
 *    [EightBitDoUltimate2CWaker.wake].
 * 3. Add another `<usb-device>` line in res/xml/usb_device_filter.xml.
 */
object DeviceRegistry {

    data class DeviceConfig(
        val vendorId: Int,
        val productId: Int,
        val name: String,
        val waker: (UsbDevice, UsbManager, ((String, String) -> Unit)?, (() -> Unit)?) -> Unit,
    )

    val DEVICES: List<DeviceConfig> = listOf(
        DeviceConfig(
            vendorId = 11720,   // 0x2DC8 = 8BitDo
            productId = 12554,  // 0x310A = Ultimate 2C Wireless (active mode)
            name = "8BitDo Ultimate 2C Wireless",
            waker = EightBitDoUltimate2CWaker::wake,
        ),
    )

    fun isSupported(vendorId: Int, productId: Int): Boolean =
        DEVICES.any { it.vendorId == vendorId && it.productId == productId }

    fun getConfig(vendorId: Int, productId: Int): DeviceConfig? =
        DEVICES.find { it.vendorId == vendorId && it.productId == productId }

    fun allPairs(): List<Pair<Int, Int>> =
        DEVICES.map { it.vendorId to it.productId }

    fun isSupportedDevice(device: UsbDevice): Boolean =
        isSupported(device.vendorId, device.productId)
}
