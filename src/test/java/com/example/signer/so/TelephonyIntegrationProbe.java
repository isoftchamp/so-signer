package com.example.signer.so;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
            if (result.length != input.length + 8
                    || result[input.length] != 'H'
                    || result[input.length + 1] != 'A'
                    || result[input.length + 2] != 'S'
                    || result[input.length + 3] != 'H') {
                throw new AssertionError(
                        "Native file processing output is invalid");
            }
            System.out.println("Native file processing: OK");
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
