package com.example.signer.so;

/**
 * Java declaration of the JNI contract implemented by the Android library.
 *
 * <p>Do not call these methods directly from the Windows JVM. The library is
 * ARM Android code, so {@link AndroidSoRuntime} invokes these signatures
 * inside unidbg's emulated VM. The declarations remain here so the Java and
 * C++ sides of the JNI interface are explicit and searchable.</p>
 */
public final class NativeMediaProcessor {

    private NativeMediaProcessor() {
    }

    /**
     * Returns the custom native library version.
     */
    public static native String nativeVersion();

    /**
     * Exercises Android's Java telephony APIs from native JNI and returns
     * {@code OK:<imei>} only when every emulated acquisition route agrees.
     *
     * @param context synthetic Android application context supplied by unidbg
     * @param expectedImei complete IMEI expected from every route
     */
    public static native String probeTelephony(
            Object context, String expectedImei);

    /**
     * Calculates CRC-32C for the complete file and appends {@code HASH}
     * followed by four raw little-endian checksum bytes.
     *
     * @param outputPath Android path mapped to the host output file
     * @return zero on success, otherwise a native status code
     */
    public static native int appendFileCrc32(String outputPath);
}
