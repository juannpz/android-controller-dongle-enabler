package com.acde

import android.content.Intent
import android.hardware.usb.UsbManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

    override fun getMainComponentName(): String = "acde"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        handleUsbIntent(intent)
    }

    // -----------------------------------------------------------------------
    // Gamepad event forwarding → UsbWakeModule static bridge → JS
    // -----------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device
        if (dev != null && isGamepadSource(dev)) {
            UsbWakeModule.onGamepadKey(dev.id, event.action, event.keyCode)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val dev = event.device
        if (dev != null && isGamepadSource(dev)) {
            // Forward each historical + current axis value
            val metaState = event.metaState
            var i = 0
            while (i < event.historySize) {
                forwardAxes(dev, event, i)
                i++
            }
            forwardAxes(dev, event, event.historySize) // current position
        }
        return super.onGenericMotionEvent(event)
    }

    private fun forwardAxes(dev: InputDevice, event: MotionEvent, pos: Int) {
        for (axis in ALL_AXES) {
            try {
                val rawPos = if (pos < event.historySize) pos else -1
                val value = if (rawPos >= 0) {
                    event.getHistoricalAxisValue(axis, rawPos)
                } else {
                    event.getAxisValue(axis)
                }
                if (value != 0f) {
                    UsbWakeModule.onGamepadMotion(dev.id, axis, value)
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
    }

    companion object {
        /** All motion axes we care about for gamepad diagnostics. */
        private val ALL_AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
        )

        private fun isGamepadSource(dev: InputDevice): Boolean {
            val s = dev.sources
            return (s and InputDevice.SOURCE_GAMEPAD) != 0 ||
                   (s and InputDevice.SOURCE_JOYSTICK) != 0
        }
    }
}
