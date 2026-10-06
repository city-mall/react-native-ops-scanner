import { NativeModules, Platform } from "react-native";

/**
 * Thin wrapper over the native QR / barcode scanner
 * (live.citymall.opsscanner.CodeScannerModule). Android only.
 *
 * `mode` set → the screen is locked to it and the QR / barcode switcher is
 * hidden. `mode` omitted → the switcher is shown and the user picks (it opens
 * on the last-used mode). Square frame for QR, wide rectangle for barcodes.
 */

export type ScanMode = "QR" | "BARCODE";

export interface ScannerConfig {
  mode?: ScanMode | null;
  /** Overrides the per-mode title. */
  title?: string | null;
  /** Overrides the per-mode helper line under the frame. */
  helper_text?: string | null;
  /**
   * Set → the screen stays open until it reads one of these (trimmed,
   * upper-cased) values; anything else shows a chip and scanning continues.
   * Unset → the first code inside the frame closes it.
   */
  accept_values?: string[] | null;
  /** Chip text per known-but-wrong value (normalised like accept_values). */
  reject_hints?: Record<string, string> | null;
  /** Chip text for any other value. */
  invalid_text?: string | null;
}

export interface ScanResult {
  didCancel: boolean;
  value?: string | null;
  /** ML Kit format, e.g. "QR_CODE", "CODE_128". */
  format?: string | null;
}

interface CodeScannerNativeModule {
  scan(config: ScannerConfig): Promise<ScanResult>;
}

const getNativeModule = (): CodeScannerNativeModule | undefined =>
  NativeModules?.CodeScannerModule;

/**
 * MANDATORY guard. OTA updates (Revopush / CodePush) ship JS without a native
 * binary, so callers must keep their old scan path for APKs that predate
 * CodeScannerModule.
 */
export const isNativeCodeScannerAvailable = (): boolean =>
  Platform.OS === "android" && !!getNativeModule()?.scan;

const SCAN_MODES: readonly ScanMode[] = ["QR", "BARCODE"];

/**
 * Server-sent config is untrusted shape-wise: an unknown mode becomes "unset"
 * (switcher shown) instead of being forwarded.
 */
export const normalizeScannerConfig = (
  raw: Partial<Record<keyof ScannerConfig, unknown>> | null | undefined,
): ScannerConfig => {
  const mode =
    typeof raw?.mode === "string" ? raw.mode.trim().toUpperCase() : null;
  return {
    mode: SCAN_MODES.includes(mode as ScanMode) ? (mode as ScanMode) : null,
    title: typeof raw?.title === "string" ? raw.title : null,
    helper_text: typeof raw?.helper_text === "string" ? raw.helper_text : null,
  };
};

/** Resolves `{ didCancel: true }` on close; rejects only if the screen could not run. */
export const scanCode = async (config: ScannerConfig): Promise<ScanResult> => {
  const nativeModule = getNativeModule();
  if (!nativeModule?.scan) {
    throw new Error("CodeScannerModule unavailable");
  }
  return nativeModule.scan(config);
};
