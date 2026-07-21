import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  NativeEventEmitter,
  NativeModules,
  ScrollView,
  Share,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  SafeAreaProvider,
  SafeAreaView,
} from 'react-native-safe-area-context';
import { UsbWakeModule, UsbStatus } from './src/native/UsbWakeModule';

// ---------------------------------------------------------------------------
// Event emitter & types
// ---------------------------------------------------------------------------

const eventEmitter = new NativeEventEmitter(NativeModules.UsbWakeModule);

type LogLevel = 'debug' | 'info' | 'warn' | 'error';

interface LogEntry {
  id: number;
  timestamp: string;
  level: LogLevel;
  source: 'native' | 'js';
  message: string;
}

const STATUS_CONFIG: Record<UsbStatus | 'checking', { text: string; color: string }> = {
  checking: { text: 'Checking\u2026', color: '#888888' },
  disconnected: { text: 'Disconnected', color: '#888888' },
  no_permission: { text: 'Connected without permission', color: '#f0a030' },
  ready: { text: 'Ready', color: '#30d060' },
  unsupported: { text: 'USB Host unavailable', color: '#f04040' },
};

// Human-readable labels for common hardware axes
const AXIS_LABELS: Record<number, string> = {
  0: 'X', 1: 'Y', 11: 'Z', 14: 'RZ',
  12: 'RX', 13: 'RY',
  17: 'L2', 18: 'R2',
  15: 'HatX', 16: 'HatY',
};

// Human-readable labels for common gamepad key codes
const KEY_LABELS: Record<number, string> = {
  304: 'A', 305: 'B', 306: 'X', 307: 'Y',
  308: 'L1', 309: 'R1', 312: 'Select', 313: 'Start',
  310: 'L3', 311: 'R3',
  19: 'Up', 20: 'Down', 21: 'Left', 22: 'Right',
  316: 'Home', 314: 'Screenshot',
};

// ---------------------------------------------------------------------------
// App root
// ---------------------------------------------------------------------------

export default function App() {
  return (
    <SafeAreaProvider>
      <AppContent />
    </SafeAreaProvider>
  );
}

// ---------------------------------------------------------------------------
// AppContent
// ---------------------------------------------------------------------------

function AppContent() {
  const [status, setStatus] = useState<UsbStatus | 'checking'>('checking');
  const [error, setError] = useState<string | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [logExpanded, setLogExpanded] = useState(false);

  // ---- Gamepad tester state ----
  type GamepadState = { lastKey: string | null; axes: Record<string, number> };
  const [gamepads, setGamepads] = useState<Record<number, GamepadState>>({});
  const [inputDevices, setInputDevices] = useState<ReturnType<typeof mapInputDevice>[]>([]);

  const isRequesting = useRef(false);
  const nextLogId = useRef(0);
  const logScrollRef = useRef<ScrollView>(null);
  const testerScrollRef = useRef<ScrollView>(null);
  const prevStatusRef = useRef<UsbStatus | 'checking'>('checking');
  const keyDebounce = useRef<Record<string, number>>({});

  // ---- log helpers -------------------------------------------------------

  const appendLog = useCallback(
    (level: LogLevel, source: 'native' | 'js', message: string) => {
      const entry: LogEntry = {
        id: nextLogId.current++,
        timestamp: new Date().toLocaleTimeString('es-AR', { hour12: false }),
        level,
        source,
        message,
      };
      setLogs(prev => {
        const next = [...prev, entry];
        return next.length > 200 ? next.slice(-150) : next;
      });
    },
    [],
  );

  const addJsLog = useCallback(
    (level: LogLevel, message: string) => appendLog(level, 'js', message),
    [appendLog],
  );

  const copyLogs = useCallback(async () => {
    const text = logs
      .map(e => `[${e.timestamp}] [${e.source.toUpperCase()}] [${e.level}] ${e.message}`)
      .join('\n');
    try { await Share.share({ title: 'ACDE Logs', message: text }); } catch (_) {}
  }, [logs]);

  // ---- computed values ------------------------------------------------------

  const hasAxisData = Object.values(gamepads).some(g => Object.keys(g.axes).length > 0);

  // ---- map input device ------------------------------------------------------

  function mapInputDevice(d: { id: number; name: string; vendorId: number }) {
    return { id: d.id, name: d.name, vendorId: d.vendorId };
  }

  // ---- copy tester -------------------------------------------------------

  const copyTester = useCallback(async () => {
    const lines: string[] = ['=== ACDE Gamepad Tester ===', ''];
    const entries = Object.entries(gamepads);
    if (entries.length === 0) {
      lines.push('(no gamepad input yet)');
    } else {
      for (const [id, g] of entries) {
        const dev = inputDevices.find(d => d.id === Number(id));
        lines.push(`Gamepad #${id}${dev ? ` — ${dev.name}` : ''}:`);
        lines.push(`  Last button: ${g.lastKey ?? '(none)'}`);
        const ax = Object.entries(g.axes);
        if (ax.length > 0) {
          lines.push('  Axes:');
          ax.forEach(([n, v]) => lines.push(`    ${n}: ${v}`));
        }
        lines.push('');
      }
    }
    try { await Share.share({ title: 'ACDE Tester', message: lines.join('\n') }); } catch (_) {}
  }, [gamepads, inputDevices]);

  // ---- status logic ------------------------------------------------------

  const handleStatus = useCallback(async () => {
    try {
      setError(null);
      const prev = prevStatusRef.current;
      addJsLog('info', 'checkUsbStatus()');
      const result = await UsbWakeModule.checkUsbStatus();
      if (result !== prev) {
        addJsLog('info', `Status: ${prev} → ${result}`);
        prevStatusRef.current = result;
      }
      setStatus(result);

      if (result === 'no_permission' && !isRequesting.current) {
        isRequesting.current = true;
        addJsLog('info', 'Auto-requesting permission\u2026');
        try {
          const granted = await UsbWakeModule.requestUsbPermission();
          addJsLog(granted ? 'info' : 'warn',
            `Request: ${granted ? 'GRANTED' : 'DENIED'}`);
          setStatus(granted ? 'ready' : 'no_permission');
        } catch (e: any) {
          addJsLog('error', `Request failed: ${e.message}`);
          setError(e.message ?? 'Error al solicitar permiso');
        } finally {
          isRequesting.current = false;
        }
      }
    } catch (e: any) {
      addJsLog('error', `checkUsbStatus crashed: ${e.message}`);
      setStatus('disconnected');
      setError(e.message ?? 'Error desconocido');
    }
  }, [addJsLog]);

  const requestPermission = useCallback(async () => {
    if (isRequesting.current) { addJsLog('warn', 'Request in progress'); return; }
    isRequesting.current = true;
    try {
      setError(null);
      addJsLog('info', 'Manual permission request');
      const granted = await UsbWakeModule.requestUsbPermission();
      addJsLog(granted ? 'info' : 'warn', `Manual: ${granted ? 'GRANTED' : 'DENIED'}`);
      setStatus(granted ? 'ready' : 'no_permission');
    } catch (e: any) {
      addJsLog('error', `Manual request failed: ${e.message}`);
      setError(e.message ?? 'Error al solicitar permiso');
    } finally { isRequesting.current = false; }
  }, [addJsLog]);

  // ---- refresh input devices ---------------------------------------------

  const refreshInputDevices = useCallback(async () => {
    try {
      const devs = await UsbWakeModule.listInputDevices();
      setInputDevices(devs.map(mapInputDevice));
      addJsLog('info', `Found ${devs.length} gamepad device(s)`);
    } catch (_: any) {}
  }, [addJsLog]);

  // ---- effects -----------------------------------------------------------

  useEffect(() => {
    addJsLog('info', 'Mounted — subscribing to events');
    handleStatus();
    refreshInputDevices();

    const statusSub = eventEmitter.addListener('onUsbStatusChanged', () => {
      addJsLog('debug', 'onUsbStatusChanged');
      handleStatus();
      refreshInputDevices();
    });

    const logSub = eventEmitter.addListener(
      'onNativeLog',
      (p: { level: LogLevel; message: string }) => appendLog(p.level, 'native', p.message),
    );

    const keySub = eventEmitter.addListener(
      'onGamepadKey',
      (p: { deviceId: number; action: string; keyCode: number }) => {
        const label = KEY_LABELS[p.keyCode] ?? `key_${p.keyCode}`;
        const now = Date.now();
        const debounceKey = `${p.deviceId}_${p.keyCode}_${p.action}`;
        const last = keyDebounce.current[debounceKey] ?? 0;
        if (now - last < 150) return;
        keyDebounce.current[debounceKey] = now;
        const text = `${p.action === 'down' ? '▼' : '▲'}${label}`;
        setGamepads(prev => ({
          ...prev,
          [p.deviceId]: { ...prev[p.deviceId] ?? { lastKey: null, axes: {} }, lastKey: text },
        }));
        appendLog('debug', 'native', `Gamepad ${p.action}: ${label} (dev=${p.deviceId})`);
      },
    );

    const motionSub = eventEmitter.addListener(
      'onGamepadMotion',
      (p: { deviceId: number; axis: number; value: number }) => {
        const label = AXIS_LABELS[p.axis] ?? `axis_${p.axis}`;
        setGamepads(prev => {
          const g = prev[p.deviceId] ?? { lastKey: null, axes: {} };
          return {
            ...prev,
            [p.deviceId]: { ...g, axes: { ...g.axes, [label]: Math.round(p.value * 100) / 100 } },
          };
        });
      },
    );

    return () => {
      addJsLog('debug', 'Unmounting');
      statusSub.remove();
      logSub.remove();
      keySub.remove();
      motionSub.remove();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- auto-scroll logs --------------------------------------------------

  useEffect(() => {
    if (logExpanded && logs.length > 0) {
      setTimeout(() => logScrollRef.current?.scrollToEnd({ animated: true }), 100);
    }
  }, [logs, logExpanded]);

  // ---- auto-scroll tester ------------------------------------------------

  useEffect(() => {
    if (hasAxisData) {
      setTimeout(() => testerScrollRef.current?.scrollToEnd({ animated: true }), 100);
    }
  }, [gamepads, hasAxisData]);

  // ---- render ------------------------------------------------------------

  const config = STATUS_CONFIG[status];
  const showPermissionButton = status === 'no_permission';

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <StatusBar barStyle="light-content" backgroundColor="#0d0d1a" />

      {/* ---- status area ---- */}
      <View style={styles.statusArea}>
        <Text style={styles.title}>ACDE</Text>
        <View style={[styles.statusDot, { backgroundColor: config.color }]} />
        <Text style={[styles.statusText, { color: config.color }]}>
          {config.text}
        </Text>
        {error ? <Text style={styles.error}>{error}</Text> : null}
        {showPermissionButton && (
          <TouchableOpacity style={styles.button} onPress={requestPermission} activeOpacity={0.7}>
            <Text style={styles.buttonText}>Solicitar permiso USB</Text>
          </TouchableOpacity>
        )}
      </View>

      {/* ---- gamepad tester ---- */}
      <View style={styles.testerPanel}>
        <View style={styles.testerHeader}>
          <Text style={styles.testerTitle}>Gamepad tester</Text>
          <View style={styles.testerHeaderRight}>
            <TouchableOpacity
              style={styles.testerCopyButton}
              onPress={copyTester}
              activeOpacity={0.7}>
              <Text style={styles.testerCopyText}>Copy</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={refreshInputDevices} activeOpacity={0.7}>
              <Text style={styles.testerRefresh}>⟳</Text>
            </TouchableOpacity>
          </View>
        </View>

        <ScrollView
          ref={testerScrollRef}
          style={styles.testerScroll}
          contentContainerStyle={styles.testerScrollContent}
          nestedScrollEnabled>

          {Object.keys(gamepads).length === 0 ? (
            <Text style={styles.testerNone}>Press a gamepad button to see input…</Text>
          ) : (
            Object.entries(gamepads).map(([id, g]) => {
              const dev = inputDevices.find(d => d.id === Number(id));
              return (
                <View key={id} style={styles.gamepadCard}>
                  <Text style={styles.gamepadTitle}>
                    🎮 {dev?.name ?? `Gamepad #${id}`}
                  </Text>

                  {/* Last button */}
                  <View style={styles.gamepadRow}>
                    <Text style={styles.testerLabel}>Button:</Text>
                    <Text style={[styles.testerKey, g.lastKey ? styles.testerKeyLit : styles.testerKeyDim]}>
                      {g.lastKey ?? '(none)'}
                    </Text>
                  </View>

                  {/* Axes */}
                  {Object.keys(g.axes).length > 0 && (
                    <>
                      <Text style={styles.testerLabel}>Axes:</Text>
                      {Object.entries(g.axes).map(([name, val]) => (
                        <View key={name} style={styles.axisRow}>
                          <Text style={styles.axisName}>{name}</Text>
                          <View style={styles.axisBarBg}>
                            <View style={[styles.axisBarFill, { width: `${(Math.abs(val) * 100).toFixed(0)}%` as any }]} />
                          </View>
                          <Text style={styles.axisVal}>{val}</Text>
                        </View>
                      ))}
                    </>
                  )}
                </View>
              );
            })
          )}
        </ScrollView>
      </View>

      {/* ---- log panel ---- */}
      <View style={[styles.logPanel, logExpanded && styles.logPanelExpanded]}>
        <View style={styles.logHeader}>
          <TouchableOpacity
            style={styles.logHeaderLeft}
            onPress={() => setLogExpanded(v => !v)}
            activeOpacity={0.7}>
            <Text style={styles.logHeaderText}>
              Logs ({logs.length}) {logExpanded ? '▲' : '▼'}
            </Text>
          </TouchableOpacity>
          {logExpanded && logs.length > 0 && (
            <TouchableOpacity style={styles.logCopyButton} onPress={copyLogs} activeOpacity={0.7}>
              <Text style={styles.logCopyText}>Copy</Text>
            </TouchableOpacity>
          )}
        </View>
        {logExpanded && (
          <ScrollView ref={logScrollRef} style={styles.logScroll} contentContainerStyle={styles.logScrollContent}>
            {logs.length === 0 && <Text style={[styles.logEntry, styles.logEmpty]}>Sin entradas aún...</Text>}
            {logs.map(entry => (
              <Text key={entry.id} style={[styles.logEntry, logColors[entry.level]]}>
                [{entry.timestamp}] [{entry.source.toUpperCase()}] {entry.message}
              </Text>
            ))}
          </ScrollView>
        )}
      </View>
    </SafeAreaView>
  );
}

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const logColors: Record<LogLevel, { color: string }> = {
  debug: { color: '#778899' },
  info: { color: '#b0c4de' },
  warn: { color: '#f0a030' },
  error: { color: '#f04040' },
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0d0d1a' },

  /* ---- status ---- */
  statusArea: { alignItems: 'center', justifyContent: 'center', padding: 16, paddingTop: 20 },
  title: { color: '#e0e0e0', fontSize: 24, fontWeight: '700', marginBottom: 16 },
  statusDot: { borderRadius: 8, height: 16, marginBottom: 8, width: 16 },
  statusText: { fontSize: 16, fontWeight: '500', marginBottom: 12 },
  error: { color: '#f04040', fontSize: 12, marginBottom: 8, textAlign: 'center' },
  button: {
    backgroundColor: '#16213e', borderColor: '#0f3460',
    borderRadius: 8, borderWidth: 1, paddingHorizontal: 24, paddingVertical: 10,
  },
  buttonText: { color: '#e0e0e0', fontSize: 14, fontWeight: '600' },

  /* ---- tester ---- */
  testerPanel: {
    backgroundColor: '#0f0f1f', borderTopColor: '#1a1a3e', borderTopWidth: 1,
    paddingTop: 8, paddingBottom: 4, maxHeight: 280, flexShrink: 0,
  },
  testerScroll: { flexGrow: 0 },
  testerScrollContent: { paddingHorizontal: 10, paddingBottom: 8 },
  testerHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 10, marginBottom: 4 },
  testerHeaderRight: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  testerTitle: { color: '#b0c4de', fontSize: 13, fontWeight: '700' },
  testerCopyButton: { paddingHorizontal: 8, paddingVertical: 2, borderColor: '#334', borderWidth: 1, borderRadius: 4 },
  testerCopyText: { color: '#8899aa', fontSize: 10, fontFamily: 'monospace' },
  testerRefresh: { color: '#778899', fontSize: 18 },
  testerLabel: { color: '#667788', fontSize: 11, marginTop: 4, marginBottom: 2 },
  testerNone: { color: '#445566', fontSize: 11, fontStyle: 'italic' },
  testerKey: { fontSize: 15, fontWeight: '700', fontFamily: 'monospace' },
  testerKeyLit: { color: '#30d060' },
  testerKeyDim: { color: '#445566', fontSize: 12 },
  gamepadCard: {
    backgroundColor: '#12122a', borderRadius: 6, padding: 8, marginBottom: 6,
    borderColor: '#1a1a3e', borderWidth: 1,
  },
  gamepadTitle: { color: '#8ab4d8', fontSize: 12, fontWeight: '700', marginBottom: 4 },
  gamepadRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  axisRow: { flexDirection: 'row', alignItems: 'center', marginVertical: 1 },
  axisName: { color: '#8ab4d8', fontSize: 10, width: 32, fontFamily: 'monospace' },
  axisBarBg: { flex: 1, height: 6, backgroundColor: '#1a1a30', borderRadius: 3, marginHorizontal: 6 },
  axisBarFill: { height: 6, backgroundColor: '#30d060', borderRadius: 3 },
  axisVal: { color: '#8899aa', fontSize: 10, width: 40, fontFamily: 'monospace', textAlign: 'right' },

  /* ---- log panel ---- */
  logPanel: { backgroundColor: '#0a0a16', borderTopColor: '#1a1a3e', borderTopWidth: 1 },
  logPanelExpanded: { maxHeight: '40%', flexShrink: 0 },
  logHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingRight: 10 },
  logHeaderLeft: { flex: 1, paddingVertical: 8, paddingHorizontal: 14 },
  logHeaderText: { color: '#8899aa', fontSize: 12, fontWeight: '600', fontFamily: 'monospace' },
  logCopyButton: { paddingHorizontal: 10, paddingVertical: 3, borderColor: '#334', borderWidth: 1, borderRadius: 4 },
  logCopyText: { color: '#8899aa', fontSize: 11, fontFamily: 'monospace' },
  logScroll: { flexGrow: 0 },
  logScrollContent: { paddingHorizontal: 10, paddingBottom: 12 },
  logEntry: { fontSize: 11, fontFamily: 'monospace', lineHeight: 16 },
  logEmpty: { color: '#556', fontStyle: 'italic' },
});
