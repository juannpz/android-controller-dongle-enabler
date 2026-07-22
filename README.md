# ACDE — Android Controller Dongle Enabler

**Automatically wakes your 8BitDo controller when Android can't do it on its own.**

Some Android builds fail to properly probe the kernel HID driver when an 8BitDo USB dongle connects. The dongle appears in the system, USB/OTG permission is granted but the controller never responds.

ACDE detects the dongle, runs the exact USB HID initialization sequence the Linux kernel would use, and hands the device back. From that point on, the controller works system-wide — in any emulator, game, or app.

## Supported Devices

| Controller | VID:PID | Status |
|---|---|---|
| 8BitDo Ultimate 2C Wireless (USB dongle) | `11720:12554` | ✅ |
| Multiple dongles simultaneously | — | ✅ |

> The wake procedure is device-specific. Adding more controllers: add an entry in `DeviceRegistry.kt`, create a waker object, and add the VID:PID to `usb_device_filter.xml`.

## How It Works

### First time (one click)

1. Install the app
2. Plug in the dongle and power on your controller
3. Android shows: _"Allow ACDE to access 8BitDo Ultimate 2C?"_
4. Tap **OK**.
5. Done. Your controller works everywhere.

### Every time after that

Reconnect the controller, reboot your phone, plug into a different hub — it doesn't matter. The app's broadcast receiver detects the dongle, runs the USB initialization sequence on a background thread, and hands the device back to the kernel. No app launch needed.

### Multiple controllers

Plug in up to 4 dongles. Each asks for permission once, then works independently.

### Why does it ask for permission every time I reconnect?

This is normal Android behavior — not an ACDE bug. Android ties USB permissions to a device's unique identity (VID + PID + serial number). The 8BitDo dongle uses a **dynamic serial number** that changes on each power cycle, so Android sees it as a new device and requires a fresh grant. USB hubs can also alter the identity when you move ports or power-cycle the hub. ACDE auto-requests permission as soon as the device connects, so the dialog pops up immediately — just tap OK.

### Why does the controller name change in the tester?

The 8BitDo dongle exposes multiple interfaces — keyboard and gamepad. Android registers both as separate input devices with different IDs. Gamepad events can arrive from either one, and the tester's card title shows whichever name matches the event's device ID at that moment. This is cosmetic — the controller works regardless.

### Setting up emulators and games

When configuring emulators like Eden, NetherSX2, or Winlator, you'll see two entries for the 8BitDo controller in the input device list:

- `8BitDo Ultimate 2C Wireless Controller` ← **Pick this one**
- `8BitDo 8BitDo Ultimate 2C Wireless Controller Keyboard` ← Ignore this

Always choose the entry **without "Keyboard" in the name**. If your buttons and sticks respond, you picked the right one. ACDE only initializes the gamepad interface — the keyboard entry is just Android registering the dongle's other USB interface and won't work as a controller.

## What's Happening Under the Hood

The 8BitDo USB dongle firmware requires a specific HID initialization sequence to start transmitting gamepad data. The Linux kernel normally handles this, but on many Android builds the HID driver doesn't probe correctly.

ACDE replicates the exact sequence the kernel would use:

1. Claim the vendor-specific interface (#0) and HID gamepad interface (#2)
2. Send `SET_IDLE`, `GET_DESCRIPTOR`, `SET_REPORT` via USB control transfers
3. Send an interrupt OUT payload to wake the firmware
4. Read input reports to confirm the device is alive
5. Release all interfaces — the kernel HID driver rebinds and creates `/dev/input/eventX`

The keyboard interface (#1) is never touched. It keeps working normally.

This sequence was captured via `usbmon` from a working Linux desktop session.

## Architecture

```
AndroidManifest
     │
     ├──► UsbWakeReceiver  ──► DongleWaker  ──► DeviceRegistry  ──► device-specific waker
     │    (catches USB        (dispatcher)      (VID:PID → waker map)   (e.g. EightBitDo…)
     │     attach events)
     │
     ├──► MainActivity  ──► UsbWakeModule  ──► App.tsx
     │    (forwards HW        (RN bridge)        (UI, status, tester)
     │     gamepad events)
     │
     └──► usb_device_filter.xml  ──► Android auto-shows permission dialog
```

| Component | What it does |
|---|---|
| `DeviceRegistry` | Maps VID:PID pairs to their device-specific wakers |
| `DongleWaker` | Dispatcher — looks up the device in `DeviceRegistry` and calls the right waker |
| `EightBitDoUltimate2CWaker` | The actual USB HID init sequence for the 8BitDo Ultimate 2C (captured via `usbmon`) |
| `UsbWakeReceiver` | Manifest broadcast receiver — catches `USB_DEVICE_ATTACHED`, requests permission, triggers wake |
| `UsbWakeModule` | React Native Native Module — bridges USB status to JS, emits gamepad events |
| `MainActivity` | Forwards hardware `KeyEvent`/`MotionEvent` to the JS tester UI |
| `App.tsx` | Status indicator, permission button, per-gamepad tester, debug logs |

## Tech Stack

- React Native 0.86 (Bare Workflow, New Architecture)
- Kotlin native modules
- No native libraries beyond what RN ships
- Minimum SDK 24, target SDK 36

## Dev

```bash
bun install
bunx react-native run-android
```

## License

MIT
