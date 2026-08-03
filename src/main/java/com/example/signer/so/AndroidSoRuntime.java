package com.example.signer.so;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Owns the in-process Android emulator used to load the ARM Android library.
 *
 * <p>This follows the same bootstrap sequence as the PhonePe reference project.
 * It is deliberately separate from the JavaFX controller so native integration
 * details can evolve without adding emulator code to the UI layer.</p>
 */
public final class AndroidSoRuntime extends AbstractJni implements AutoCloseable {

    public static final String LIBRARY_FILE_NAME =
            "libphonepe-cryptography-support-lib.so";

    private static final String CLASSPATH_LIBRARY =
            "native/" + LIBRARY_FILE_NAME;
    private static final String LIBRARY_PATH_PROPERTY =
            "so.signer.native.path";
    private static final String LIBRARY_PATH_ENVIRONMENT =
            "SO_SIGNER_NATIVE_PATH";
    private static final byte[] PHONEPE_SIGNATURE = {
            78, 80, -74, -19, 26, -70, -71, 69, -105, 104,
            -65, -64, -39, 16, -35, 26, 83, 22, -71, 127
    };

    private AndroidEmulator emulator;
    private VM vm;
    private DvmClass encryptionUtils;
    private Path extractedLibrary;
    private String deviceId = "";

    /**
     * Starts a 32-bit Android VM and calls the library's JNI_OnLoad function.
     *
     * <p>The current PhonePe library is ARM32. When replacing it with a
     * 64-bit library, change {@code for32Bit()} to {@code for64Bit()} and use
     * a compatible backend/library set.</p>
     */
    public AndroidSoRuntime() throws IOException {
        try {
            emulator = AndroidEmulatorBuilder.for32Bit()
                    .setProcessName("com.phonepe.app")
                    .addBackendFactory(new Unicorn2Factory(true))
                    .build();
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));

            vm = emulator.createDalvikVM();
            vm.setVerbose(false);
            vm.setJni(this);

            File library = resolveLibraryFile();
            DalvikModule module = vm.loadLibrary(library, false);
            module.callJNI_OnLoad(emulator);
            encryptionUtils = vm.resolveClass(
                    "com/phonepe/networkclient/rest/EncryptionUtils");
        } catch (RuntimeException | IOException exception) {
            closeAfterInitializationFailure();
            throw exception;
        }
    }

    AndroidEmulator getEmulator() {
        return emulator;
    }

    VM getVm() {
        return vm;
    }

    /**
     * Invokes the same PhonePe checksum function used by the reference project.
     *
     * @param currentDeviceId test device identifier exposed to the emulated JNI
     * @param urlPath arbitrary request path included in the checksum
     * @param body bytes supplied as the request body
     * @return the native checksum response bytes
     */
    public synchronized byte[] generatePhonePeChecksum(
            String currentDeviceId, String urlPath, byte[] body) {
        deviceId = currentDeviceId;
        String randomUuid = UUID.randomUUID().toString();
        DvmObject<?> context = vm.resolveClass("android/content/Context")
                .newObject(null);
        ByteArray result = (ByteArray) encryptionUtils.callStaticJniMethodObject(
                emulator,
                "nmcs([B[B[BLjava/lang/Object;)[B",
                urlPath.getBytes(StandardCharsets.UTF_8),
                body,
                randomUuid.getBytes(StandardCharsets.UTF_8),
                context);
        if (result == null) {
            throw new IllegalStateException(
                    "PhonePe native checksum function returned null");
        }
        return result.getValue();
    }

    /*
     * The methods below emulate the small portion of Android and PhonePe's
     * Java helper layer that this particular native function calls. They are
     * intentionally copied in behavior from the working PhonePe reference.
     * A different .so will usually require a different set of callbacks.
     */
    @Override
    public DvmObject<?> callObjectMethodV(
            BaseVM baseVm, DvmObject<?> object, String signature,
            VaList arguments) {
        switch (signature) {
            case "[B->getPackageManager()Landroid/content/pm/PackageManager;":
                return baseVm.resolveClass("android/content/pm/PackageManager")
                        .newObject(null);
            case "[B->getPackageName()Ljava/lang/String;":
            case "android/content/Context->getPackageName()Ljava/lang/String;":
                return new StringObject(baseVm, "com.phonepe.app");
            case "android/content/pm/Signature->toByteArray()[B":
                return new ByteArray(baseVm, PHONEPE_SIGNATURE);
            default:
                return super.callObjectMethodV(
                        baseVm, object, signature, arguments);
        }
    }

    @Override
    public DvmObject<?> callStaticObjectMethodV(
            BaseVM baseVm, DvmClass dvmClass, String signature,
            VaList arguments) {
        switch (signature) {
            case "com/phonepe/networkclient/utils/CH->b([B)[B":
                return new ByteArray(baseVm, digest(
                        "SHA-256", bytesArgument(arguments, 0)));
            case "com/phonepe/networkclient/utils/CH->ba([B)[B":
                byte[] signatureBytes = bytesArgument(arguments, 0);
                if (Arrays.equals(signatureBytes, PHONEPE_SIGNATURE)) {
                    return new ByteArray(baseVm, signatureBytes);
                }
                return new ByteArray(baseVm, digest("SHA-1", signatureBytes));
            case "com/phonepe/networkclient/utils/CH->crb([B)[B":
                return new ByteArray(baseVm, Base64.getEncoder().encode(
                        bytesArgument(arguments, 0)));
            case "com/phonepe/networkclient/utils/CH->as([B[B)[B":
                return new ByteArray(baseVm, encryptAesGcm(
                        bytesArgument(arguments, 0),
                        bytesArgument(arguments, 1)));
            case "com/phonepe/networkclient/utils/CH->fd()[B":
                return new ByteArray(baseVm,
                        deviceId.getBytes(StandardCharsets.UTF_8));
            case "com/phonepe/networkclient/utils/CH->ebr()[B":
                return new ByteArray(baseVm,
                        Long.toString(System.currentTimeMillis())
                                .getBytes(StandardCharsets.UTF_8));
            default:
                return super.callStaticObjectMethodV(
                        baseVm, dvmClass, signature, arguments);
        }
    }

    @Override
    public void callStaticVoidMethodV(
            BaseVM baseVm, DvmClass dvmClass, String signature,
            VaList arguments) {
        if ("com/phonepe/networkclient/utils/CH->printByteLog([B)V"
                .equals(signature)) {
            return;
        }
        super.callStaticVoidMethodV(baseVm, dvmClass, signature, arguments);
    }

    @Override
    public DvmObject<?> getObjectField(
            BaseVM baseVm, DvmObject<?> object, String signature) {
        if ("android/content/pm/PackageInfo->signatures:"
                .concat("[Landroid/content/pm/Signature;")
                .equals(signature)) {
            return new ArrayObject(baseVm
                    .resolveClass("android/content/pm/Signature")
                    .newObject(null));
        }
        return super.getObjectField(baseVm, object, signature);
    }

    private byte[] bytesArgument(VaList arguments, int index) {
        return (byte[]) arguments.getObjectArg(index).getValue();
    }

    private byte[] digest(String algorithm, byte[] value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    algorithm + " is unavailable", exception);
        }
    }

    private byte[] encryptAesGcm(byte[] keyBytes, byte[] value) {
        try {
            SecretKeySpec key = new SecretKeySpec(
                    Arrays.copyOfRange(keyBytes, 0, 16), "AES");
            byte[] initializationVector = new byte[12];
            new SecureRandom().nextBytes(initializationVector);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key,
                    new GCMParameterSpec(128, initializationVector));
            byte[] encrypted = cipher.doFinal(value);
            byte[] result = new byte[
                    initializationVector.length + encrypted.length];
            System.arraycopy(initializationVector, 0, result, 0,
                    initializationVector.length);
            System.arraycopy(encrypted, 0, result,
                    initializationVector.length, encrypted.length);
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("AES-GCM encryption failed",
                    exception);
        }
    }

    private File resolveLibraryFile() throws IOException {
        File configured = existingFile(System.getProperty(LIBRARY_PATH_PROPERTY));
        if (configured == null) {
            configured = existingFile(System.getenv(LIBRARY_PATH_ENVIRONMENT));
        }
        if (configured != null) {
            return configured;
        }

        InputStream resource = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CLASSPATH_LIBRARY);
        if (resource != null) {
            extractedLibrary = Files.createTempFile("so-signer-native-", ".so");
            extractedLibrary.toFile().deleteOnExit();
            try (InputStream input = resource) {
                Files.copy(input, extractedLibrary,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return extractedLibrary.toFile();
        }

        /*
         * Development fallback: use the PhonePe project copy when the packaged
         * resource is intentionally removed. A replacement library can also be
         * supplied with -Dso.signer.native.path=<path>.
         */
        Path phonePeLibrary = Paths.get(System.getProperty("user.home"),
                "Downloads", "phonepe", "src", "main", "resources", "native",
                LIBRARY_FILE_NAME);
        if (Files.isRegularFile(phonePeLibrary)) {
            return phonePeLibrary.toFile();
        }

        throw new FileNotFoundException(
                "Android native library not found. Put " + LIBRARY_FILE_NAME
                        + " in src/main/resources/native, set -D"
                        + LIBRARY_PATH_PROPERTY + "=<path>, or set "
                        + LIBRARY_PATH_ENVIRONMENT + ".");
    }

    private File existingFile(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        File file = new File(value.trim());
        return file.isFile() ? file : null;
    }

    private void closeAfterInitializationFailure() {
        try {
            close();
        } catch (IOException ignored) {
            // Preserve the original initialization exception.
        }
    }

    @Override
    public void close() throws IOException {
        if (emulator != null) {
            emulator.close();
            emulator = null;
            vm = null;
        }
        if (extractedLibrary != null) {
            try {
                Files.deleteIfExists(extractedLibrary);
            } catch (IOException ignored) {
                /*
                 * On Windows the native backend may retain a mapped-file
                 * handle until JVM termination. deleteOnExit() was registered
                 * when the file was created, so shutdown must not fail here.
                 */
            }
            extractedLibrary = null;
        }
    }
}
