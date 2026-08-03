package com.example.signer.so;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        byte[] prefix = readPrefix(input);
        byte[] result = android.generatePhonePeChecksum(
                "so-signer-e2e-test",
                "/file-checksum/" + input.getFileName(),
                prefix);
        String checksum = new String(result, StandardCharsets.UTF_8);

        /*
         * Test-only output: this proves that the packaged ARM .so loaded,
         * JNI_OnLoad ran, the required callbacks were handled, and nmcs
         * returned a value for bytes from the selected file.
         */
        System.out.println("[PhonePe SO test] File: " + input.toAbsolutePath());
        System.out.println("[PhonePe SO test] Bytes read: " + prefix.length);
        System.out.println("[PhonePe SO test] Generated checksum: " + checksum);

        /*
         * TODO: Replace the temporary copy below with your media JNI call.
         *
         * The PhonePe reference resolves a DvmClass and calls a registered JNI
         * signature through callStaticJniMethodObject(...). Your media .so will
         * require its own:
         *
         * 1. Emulated Java class name.
         * 2. Exact JNI method signature and argument types.
         * 3. AbstractJni callback overrides for Android methods it expects.
         * 4. File mapping if native code calls open()/fopen().
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

    private byte[] readPrefix(Path input) throws IOException {
        try (InputStream stream = Files.newInputStream(input)) {
            return stream.readNBytes(1024);
        }
    }

    private AndroidSoRuntime runtime() throws IOException {
        if (runtime == null) {
            runtime = new AndroidSoRuntime();
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
