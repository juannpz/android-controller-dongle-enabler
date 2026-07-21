package com.acde

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * One-shot wake procedure for the 8BitDo dongle.
 *
 * Replicates the exact USB HID initialization sequence the Linux kernel
 * uses (captured via usbmon). The dongle firmware does not start
 * transmitting gamepad data until it receives specific SET_REPORT
 * commands and an interrupt OUT payload.
 *
 * After the wake the caller must release all claimed interfaces and
 * close the connection so the kernel HID driver can rebind.
 */
object DongleWaker {

    private const val TAG = "DongleWaker"

    fun wake(
        device: UsbDevice,
        usbManager: UsbManager,
        onLog: ((String, String) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        Thread {
            var conn: android.hardware.usb.UsbDeviceConnection? = null
            var claimed0 = false
            var claimed2 = false
            try {
                conn = usbManager.openDevice(device)
                if (conn == null) { onLog?.invoke("w", "openDevice failed"); return@Thread }
                // ---- Log interfaces ----
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    onLog?.invoke("i", "iface #$i: c=${intf.interfaceClass} sc=${intf.interfaceSubclass} p=${intf.interfaceProtocol}")
                }

                // ---- Claim iface #0 (vendor-specific, carries gamepad data) ----
                try { conn.claimInterface(device.getInterface(0), true); claimed0 = true; onLog?.invoke("i", "claimed #0") }
                catch (e: Exception) { onLog?.invoke("w", "claim #0 err: ${e.message}") }

                // ---- Claim iface #2 (HID gamepad) ----
                try { conn.claimInterface(device.getInterface(2), true); claimed2 = true; onLog?.invoke("i", "claimed #2") }
                catch (e: Exception) { onLog?.invoke("w", "claim #2 err: ${e.message}") }

                // ---- Linux kernel init sequence ----

                // 1. SET_IDLE iface #1 (keyboard — control transfer, no claim needed)
                try { conn.controlTransfer(0x21, 0x0A, 0, 1, null, 0, 1000) } catch (_: Exception) {}

                // 2. GET_DESCRIPTOR HID Report iface #1
                val d1 = ByteArray(512)
                try { conn.controlTransfer(0x81, 0x06, 0x2200, 1, d1, d1.size, 2000) } catch (_: Exception) {}

                // 3. SET_REPORT 01 00 → iface #1
                try { conn.controlTransfer(0x21, 0x09, 0x0201, 1, byteArrayOf(0x01, 0x00), 2, 1000) } catch (_: Exception) {}

                // 4. Interrupt OUT 01 03 02 on iface #0 endpoint
                if (claimed0) {
                    val intf0 = device.getInterface(0)
                    for (j in 0 until intf0.endpointCount) {
                        val ep = intf0.getEndpoint(j)
                        if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                            try { conn.bulkTransfer(ep, byteArrayOf(0x01, 0x03, 0x02), 3, 1000) } catch (_: Exception) {}
                            break
                        }
                    }
                }

                // 5. SET_REPORT 20 01 0e 00... → iface #2 (gamepad)
                if (claimed2) {
                    try { conn.controlTransfer(0x21, 0x09, 0x0220, 2,
                        byteArrayOf(0x20, 0x01, 0x0e, 0,0,0,0,0,0,0,0,0,0,0,0), 15, 1000) } catch (_: Exception) {}
                }

                // 6. SET_IDLE iface #2
                try { conn.controlTransfer(0x21, 0x0A, 0, 2, null, 0, 1000) } catch (_: Exception) {}

                // 7. GET_DESCRIPTOR HID Report iface #2
                val d2 = ByteArray(512)
                try { conn.controlTransfer(0x81, 0x06, 0x2200, 2, d2, d2.size, 2000) } catch (_: Exception) {}

                // 8. Read reports from iface #0 (gamepad data endpoint)
                var epIn: UsbEndpoint? = null
                if (claimed0) {
                    val intf0 = device.getInterface(0)
                    for (j in 0 until intf0.endpointCount) {
                        val ep = intf0.getEndpoint(j)
                        if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                            epIn = ep; break
                        }
                    }
                    if (epIn != null) {
                        val buf = ByteArray(epIn.maxPacketSize)
                        for (i in 1..10) {
                            try { conn.bulkTransfer(epIn, buf, buf.size, 500) } catch (_: Exception) { break }
                        }
                    }
                }

                // 9. GET_REPORT from iface #0
                val r = ByteArray(32)
                try { conn.controlTransfer(0xC1, 0x01, 0x0100, 0, r, r.size, 1000) } catch (_: Exception) {}

                // 10. Second SET_REPORT round — controller re-sync
                //     (kernel sends these after the first round, usbmon capture confirms)
                try { conn.controlTransfer(0x21, 0x09, 0x0201, 1, byteArrayOf(0x01, 0x01), 2, 1000) } catch (_: Exception) {}
                if (claimed2) {
                    try { conn.controlTransfer(0x21, 0x09, 0x0220, 2,
                        byteArrayOf(0x20, 0x01, 0x0e, 0x01, 0,0,0,0,0,0,0,0,0,0,0), 15, 1000) } catch (_: Exception) {}
                }

                // 11. Read more reports — give controller time to re-sync
                if (epIn != null) {
                    val buf = ByteArray(epIn.maxPacketSize)
                    for (i in 1..12) {
                        try { conn.bulkTransfer(epIn, buf, buf.size, 500) } catch (_: Exception) { break }
                    }
                }

                onLog?.invoke("i", "Wake OK")

            } catch (e: Exception) {
                onLog?.invoke("e", "Wake err: ${e.message}")
            } finally {
                // Release claimed interfaces
                if (claimed2) try { conn?.releaseInterface(device.getInterface(2)) } catch (_: Exception) {}
                if (claimed0) try { conn?.releaseInterface(device.getInterface(0)) } catch (_: Exception) {}
                try { conn?.close() } catch (_: Exception) {}
                try { usbManager.deviceList } catch (_: Exception) {}
                onComplete?.invoke()
            }
        }.apply { name = "DongleWake"; isDaemon = true; start() }
    }
}
