const mockScan = jest.fn();
const mockNativeModules: { CodeScannerModule?: { scan: jest.Mock } } = {};
const mockPlatform = { OS: "android" };

jest.mock(
  "react-native",
  () => ({ NativeModules: mockNativeModules, Platform: mockPlatform }),
  { virtual: true },
);

import {
  isNativeCodeScannerAvailable,
  normalizeScannerConfig,
  scanCode,
} from "../index";

beforeEach(() => {
  mockScan.mockReset();
  mockNativeModules.CodeScannerModule = { scan: mockScan };
  mockPlatform.OS = "android";
});

describe("normalizeScannerConfig", () => {
  it("keeps a known mode, trimmed and upper-cased", () => {
    expect(normalizeScannerConfig({ mode: " qr " }).mode).toBe("QR");
    expect(normalizeScannerConfig({ mode: "barcode" }).mode).toBe("BARCODE");
  });

  it("drops an unknown or non-string mode so the switcher is shown", () => {
    expect(normalizeScannerConfig({ mode: "ANY" }).mode).toBeNull();
    expect(normalizeScannerConfig({ mode: 1 }).mode).toBeNull();
    expect(normalizeScannerConfig(null).mode).toBeNull();
  });

  it("keeps only string title / helper_text", () => {
    expect(
      normalizeScannerConfig({ title: "Scan rack", helper_text: 5 }),
    ).toEqual({ mode: null, title: "Scan rack", helper_text: null });
  });
});

describe("isNativeCodeScannerAvailable", () => {
  it("is false when the binary has no module", () => {
    mockNativeModules.CodeScannerModule = undefined;
    expect(isNativeCodeScannerAvailable()).toBe(false);
  });

  it("is true on Android with the module", () => {
    expect(isNativeCodeScannerAvailable()).toBe(true);
  });

  it("is false on iOS even with a module", () => {
    mockPlatform.OS = "ios";
    expect(isNativeCodeScannerAvailable()).toBe(false);
  });
});

describe("scanCode", () => {
  it("rejects when the module is missing", async () => {
    mockNativeModules.CodeScannerModule = undefined;
    await expect(scanCode({ mode: "QR" })).rejects.toThrow(
      "CodeScannerModule unavailable",
    );
  });

  it("forwards the config and returns the native result", async () => {
    mockScan.mockResolvedValue({ didCancel: false, value: "R-12", format: "QR_CODE" });
    await expect(scanCode({ mode: "QR" })).resolves.toEqual({
      didCancel: false,
      value: "R-12",
      format: "QR_CODE",
    });
    expect(mockScan).toHaveBeenCalledWith({ mode: "QR" });
  });
});
