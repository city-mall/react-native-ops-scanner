"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.scanCode = exports.normalizeScannerConfig = exports.isNativeCodeScannerAvailable = void 0;
const react_native_1 = require("react-native");
const getNativeModule = () => react_native_1.NativeModules?.CodeScannerModule;
/**
 * MANDATORY guard. OTA updates (Revopush / CodePush) ship JS without a native
 * binary, so callers must keep their old scan path for APKs that predate
 * CodeScannerModule.
 */
const isNativeCodeScannerAvailable = () => react_native_1.Platform.OS === "android" && !!getNativeModule()?.scan;
exports.isNativeCodeScannerAvailable = isNativeCodeScannerAvailable;
const SCAN_MODES = ["QR", "BARCODE"];
/**
 * Server-sent config is untrusted shape-wise: an unknown mode becomes "unset"
 * (switcher shown) instead of being forwarded.
 */
const normalizeScannerConfig = (raw) => {
    const mode = typeof raw?.mode === "string" ? raw.mode.trim().toUpperCase() : null;
    return {
        mode: SCAN_MODES.includes(mode) ? mode : null,
        title: typeof raw?.title === "string" ? raw.title : null,
        helper_text: typeof raw?.helper_text === "string" ? raw.helper_text : null,
    };
};
exports.normalizeScannerConfig = normalizeScannerConfig;
/** Resolves `{ didCancel: true }` on close; rejects only if the screen could not run. */
const scanCode = async (config) => {
    const nativeModule = getNativeModule();
    if (!nativeModule?.scan) {
        throw new Error("CodeScannerModule unavailable");
    }
    return nativeModule.scan(config);
};
exports.scanCode = scanCode;
