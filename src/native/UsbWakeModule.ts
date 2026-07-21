import { NativeModules } from 'react-native';

export type UsbStatus = 'disconnected' | 'no_permission' | 'ready' | 'unsupported';

export interface InputDeviceInfo {
  id: number;
  name: string;
  vendorId: number;
  productId: number;
  hasGamepad: boolean;
  hasJoystick: boolean;
  isVirtual: boolean;
  sources: number;
}

interface UsbWakeModuleInterface {
  checkUsbStatus(): Promise<UsbStatus>;
  requestUsbPermission(): Promise<boolean>;
  listInputDevices(): Promise<InputDeviceInfo[]>;
}

export const UsbWakeModule = NativeModules.UsbWakeModule as UsbWakeModuleInterface;
