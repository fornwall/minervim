package net.fornwall.androidneovim;

final class NativeBridge {
    static {
        System.loadLibrary("neovim");
    }

    private NativeBridge() {}

    static native void start(String filesDir, String socketPath);

    static native String failure();
}
