package com.acde

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

class UsbWakeModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "UsbWakeModule"
        private const val TAG = "UsbWakeModule"
        private const val ACTION_USB_PERMISSION = "com.acde.USB_PERMISSION"

        @Volatile private var instance: UsbWakeModule? = null

        @JvmStatic fun onGamepadKey(deviceId: Int, action: Int, keyCode: Int) {
            instance?.emitGamepadKey(deviceId, action, keyCode)
        }
        @JvmStatic fun onGamepadMotion(deviceId: Int, axis: Int, value: Float) {
            instance?.emitGamepadMotion(deviceId, axis, value)
        }
    }

    init { instance = this }

    private fun emitGamepadKey(deviceId: Int, action: Int, keyCode: Int) {
        try {
            val p = Arguments.createMap().apply {
                putInt("deviceId", deviceId)
                putString("action", if (action == KeyEvent.ACTION_DOWN) "down" else "up")
                putInt("keyCode", keyCode)
            }
            reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onGamepadKey", p)
        } catch (_: Exception) {}
    }

    private fun emitGamepadMotion(deviceId: Int, axis: Int, value: Float) {
        try {
            val p = Arguments.createMap().apply {
                putInt("deviceId", deviceId); putInt("axis", axis); putDouble("value", value.toDouble())
            }
            reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onGamepadMotion", p)
        } catch (_: Exception) {}
    }

    // ---- State ----

    private var permissionPromise: Promise? = null
    private var eventReceiverRegistered = false
    private var previousWakeDone = false  // remembers if we ever woke since last detach

    // ---- BroadcastReceiver ----

    @Suppress("DEPRECATION")
    private val usbEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log("Permission: granted=$granted")
                    val d = extractDevice(intent)
                    if (granted && isTarget(d)) {
                        val mgr = reactApplicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
                        if (mgr != null && d != null) {
                            previousWakeDone = true
                            doWake(d, mgr)
                        }
                    }
                    finishPermissionPromise(granted)
                    // Force status refresh so UI updates immediately
                    emitStatusChanged()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = extractDevice(intent)
                    if (!isTarget(d)) return
                    if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                        log("Detached: ${d!!.deviceName}")
                        previousWakeDone = false
                    } else {
                        log("Attached: ${d!!.deviceName}")
                        previousWakeDone = false
                    }
                    emitStatusChanged()
                }
            }
        }
    }

    private fun doWake(device: android.hardware.usb.UsbDevice, usbManager: UsbManager) {
        DongleWaker.wake(device, usbManager,
            onLog = { l, m -> if (l == "e") Log.e(TAG, m) else Log.i(TAG, "[$l] $m") },
            onComplete = { emitStatusChanged() },
        )
    }

    private fun finishPermissionPromise(value: Boolean) {
        synchronized(this) { val p = permissionPromise; permissionPromise = null; try { p?.resolve(value) } catch (_: Exception) {} }
    }

    // ---- Helpers ----

    private fun log(msg: String) { Log.i(TAG, msg) }

    private fun emitStatusChanged() {
        try { reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("onUsbStatusChanged", null) } catch (_: Exception) {}
    }

    private fun ensureReceiver() {
        if (eventReceiverRegistered) return
        synchronized(this) {
            if (eventReceiverRegistered) return
            val f = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) reactApplicationContext.registerReceiver(usbEventReceiver, f, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") reactApplicationContext.registerReceiver(usbEventReceiver, f)
            eventReceiverRegistered = true
        }
    }

    override fun getName() = NAME

    // ---- React Methods ----

    @ReactMethod
    fun checkUsbStatus(promise: Promise) {
        ensureReceiver()
        try {
            if (!reactApplicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) { promise.resolve("unsupported"); return }
            val mgr = reactApplicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (mgr == null) { promise.resolve("unsupported"); return }
            val devs = findAll(mgr)
            if (devs.isEmpty()) { promise.resolve("disconnected"); return }
            if (!devs.all { mgr.hasPermission(it) }) { promise.resolve("no_permission"); return }

            // Wake ALL matching devices that haven't been woken yet
            if (!previousWakeDone) {
                previousWakeDone = true
                for (d in devs) {
                    doWake(d, mgr)
                }
            }

            promise.resolve("ready")
        } catch (e: Exception) { promise.reject("ERR", e.message, e) }
    }

    @ReactMethod
    fun requestUsbPermission(promise: Promise) {
        ensureReceiver()
        try {
            if (!reactApplicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) { promise.reject("ERR", "No USB host"); return }
            val mgr = reactApplicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (mgr == null) { promise.reject("ERR", "No UsbManager"); return }
            val devs = findAll(mgr)
            val needPerm = devs.firstOrNull { !mgr.hasPermission(it) }
            if (needPerm == null) {
                if (devs.isEmpty()) { promise.reject("ERR", "Not connected"); return }
                if (!previousWakeDone) {
                    previousWakeDone = true
                    for (d in devs) { doWake(d, mgr) }
                }
                promise.resolve(true)
                return
            }
            // Prevent duplicate dialog: if the manifest receiver already requested
            // permission for this device, don't trigger another one.
            if (UsbWakeReceiver.isPermissionPending()) {
                log("Permission already pending — skipping duplicate request")
                promise.resolve(false)
                return
            }
            synchronized(this) {
                permissionPromise?.let { permissionPromise = null; try { it.resolve(false) } catch (_: Exception) {} }
                permissionPromise = promise
                val pi = PendingIntent.getBroadcast(reactApplicationContext, 0,
                    Intent(ACTION_USB_PERMISSION).apply { setPackage(reactApplicationContext.packageName) },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                mgr.requestPermission(needPerm, pi)
            }
        } catch (e: Exception) { promise.reject("ERR", e.message, e) }
    }

    @ReactMethod
    fun listInputDevices(promise: Promise) {
        try {
            val arr = Arguments.createArray()
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                val s = d.sources
                if ((s and InputDevice.SOURCE_GAMEPAD) == 0 && (s and InputDevice.SOURCE_JOYSTICK) == 0) continue
                arr.pushMap(Arguments.createMap().apply {
                    putInt("id", d.id); putString("name", d.name ?: "?")
                    putInt("vendorId", d.vendorId); putInt("productId", d.productId)
                    putBoolean("isVirtual", d.isVirtual); putInt("sources", s)
                })
            }
            promise.resolve(arr)
        } catch (e: Exception) { promise.reject("ERR", e.message, e) }
    }

    // ---- Lifecycle ----

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCatalystInstanceDestroy() {
        instance = null
        try { if (eventReceiverRegistered) { reactApplicationContext.unregisterReceiver(usbEventReceiver); eventReceiverRegistered = false } } catch (_: Exception) {}
        super.onCatalystInstanceDestroy()
    }

    // ---- Filters ----

    private fun isTarget(d: android.hardware.usb.UsbDevice?) =
        d != null && DeviceRegistry.isSupportedDevice(d)

    private fun findAll(mgr: UsbManager) =
        mgr.deviceList.values.filter { DeviceRegistry.isSupportedDevice(it) }

    @Suppress("DEPRECATION")
    private fun extractDevice(intent: Intent): android.hardware.usb.UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
