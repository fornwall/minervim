package com.minervim;

final class NativeBridge {
    static {
        System.loadLibrary("neovim");
    }

    private NativeBridge() {}

    static native void start(String filesDir, String cacheDir, String socketPath);

    static native String failure();

    /** Blocks until {@link #failure()} is set and returns it. */
    static native String awaitFailure();
}
