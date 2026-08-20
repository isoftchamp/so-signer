package com.example.signer.so;

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
        }
    }
}
