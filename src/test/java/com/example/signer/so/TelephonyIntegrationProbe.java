package com.example.signer.so;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

/**
 * Command-line smoke probe for the packaged Android library and unidbg
 * telephony callbacks.
 *
 * <p>After {@code mvnw.cmd package}, run with the project classes and shaded
 * dependency JAR on the classpath:
 * {@code java -cp "target\test-classes;target\classes;
 * target\so-signer-1.0-SNAPSHOT.jar"
 * com.example.signer.so.TelephonyIntegrationProbe}</p>
 */
public final class TelephonyIntegrationProbe {

    private static final String IMEI_BODY = "49015420323751";
    private static final String COMPLETE_IMEI = "490154203237518";

    private TelephonyIntegrationProbe() {
    }

    public static void main(String[] args) throws Exception {
        TelephonyProfile profile = TelephonyProfile.singleSim(IMEI_BODY);
        try (AndroidSoRuntime runtime = new AndroidSoRuntime(profile)) {
            String version = runtime.getJniVersion();
            if (!"so-signer-native/4".equals(version)) {
                throw new AssertionError(
                        "Unexpected native library version: " + version);
            }
            System.out.println("Native library: " + version);
            System.out.println("Telephony integration: "
                    + runtime.verifyTelephonyIntegration());
            verifyNativeFileProcessing(runtime);
        }
    }

    private static void verifyNativeFileProcessing(
            AndroidSoRuntime runtime) throws Exception {
        byte[] input = "native CRC smoke test"
                .getBytes(StandardCharsets.UTF_8);
        Path output = Files.createTempFile("so-signer-probe-", ".bin");
        try {
            Files.write(output, input);
            int status = runtime.appendFileCrc32WithJni(output);
            if (status != 0) {
                throw new AssertionError(
                        "Native file processing status: " + status);
            }
            byte[] result = Files.readAllBytes(output);
            if (result.length != input.length + 27) {
                throw new AssertionError(
                        "Native file processing output length is invalid");
            }
            String metadata = new String(
                    result,
                    input.length,
                    23,
                    StandardCharsets.US_ASCII);
            if (!("IMEI" + COMPLETE_IMEI + "HASH").equals(metadata)) {
                throw new AssertionError(
                        "Native file processing output is invalid");
            }
            int checksumOffset = result.length - 4;
            long actualChecksum = Integer.toUnsignedLong(
                    (result[checksumOffset] & 0xff)
                            | (result[checksumOffset + 1] & 0xff) << 8
                            | (result[checksumOffset + 2] & 0xff) << 16
                            | (result[checksumOffset + 3] & 0xff) << 24);
            CRC32C expectedChecksum = new CRC32C();
            expectedChecksum.update(input);
            if (actualChecksum != expectedChecksum.getValue()) {
                throw new AssertionError(
                        "Native CRC-32C does not match the original file");
            }
            System.out.println("Native file processing: OK");
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
