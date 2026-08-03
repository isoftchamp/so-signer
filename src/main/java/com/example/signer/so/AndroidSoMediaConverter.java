package com.example.signer.so;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Conversion boundary backed by an emulated Android runtime.
 *
 * <p>The emulator and the .so are initialized lazily on the conversion worker,
 * not on the JavaFX application thread. Calls are synchronized because one
 * unidbg VM should be treated as single-threaded unless the native wrapper is
 * specifically designed for concurrency.</p>
 */
public final class AndroidSoMediaConverter implements MediaConverter {

    private AndroidSoRuntime runtime;

    @Override
    public synchronized void convert(Path input, Path output) throws Exception {
        AndroidSoRuntime android = runtime();

        /*
         * TODO: Replace the temporary copy below with your media JNI call.
         *
         * For large video/audio files, do not read the entire file into a
         * byte[]. Prefer passing emulated paths and register an unidbg
         * IOResolver that maps those paths to input and output on the host.
         *
         * android.getVm() and android.getEmulator() intentionally expose the
         * package-local integration points needed for that implementation.
         */
        if (android.getVm() == null || android.getEmulator() == null) {
            throw new IllegalStateException("Android emulator is not initialized");
        }
        Files.copy(input, output);
    }

    private AndroidSoRuntime runtime() throws IOException {
        if (runtime == null) {
            runtime = new AndroidSoRuntime();
            System.out.println("[Custom SO] Dynamic JNI: "
                    + runtime.getDynamicJniVersion());
            System.out.println("[Custom SO] System library: "
                    + runtime.getSystemLibraryVersion());
        }
        return runtime;
    }

    @Override
    public synchronized void close() throws IOException {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
