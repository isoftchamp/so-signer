package com.example.signer.so;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.VarArg;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Owns the in-process Android emulator used to load the custom ARM library.
 *
 * <p>It is deliberately separate from the JavaFX controller so native
 * integration details can evolve without adding emulator code to the UI
 * layer.</p>
 */
public final class AndroidSoRuntime extends AbstractJni
        implements AutoCloseable, IOResolver<AndroidFileIO> {

    public static final String LIBRARY_FILE_NAME =
            "libso-signer-native.so";

    private static final String CLASSPATH_LIBRARY =
            "native/" + LIBRARY_FILE_NAME;
    private static final String BACKEND_PROPERTY =
            "so.signer.backend";
    private static final String NATIVE_CLASS_NAME =
            NativeMediaProcessor.class.getName().replace('.', '/');
    private static final String VIRTUAL_OUTPUT_PATH =
            "/sdcard/so-signer/output";

    private final String imei;
    private AndroidEmulator emulator;
    private VM vm;
    private DvmClass nativeMediaProcessor;
    private Module module;
    private Path extractedLibrary;
    private Path mappedOutput;
    private long initializationNanos;

    /**
     * Starts a 32-bit Android VM and calls the library's JNI_OnLoad function.
     *
     * <p>The custom library is currently built for ARM32. A future ARM64
     * build must use {@code for64Bit()} and a matching native resource.</p>
     */
    public AndroidSoRuntime(String imei) throws IOException {
        this.imei = Imei.complete(imei);
        long initializationStarted = System.nanoTime();
        try {
            AndroidEmulatorBuilder builder =
                    AndroidEmulatorBuilder.for32Bit();
            builder.setProcessName("com.example.signer.so");
            configureBackend(builder);
            emulator = builder.build();
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
            emulator.getSyscallHandler().addIOResolver(this);

            vm = emulator.createDalvikVM();
            vm.setVerbose(false);
            vm.setJni(this);

            /*
             * Resolve the synthetic class before JNI_OnLoad because the
             * library dynamically registers its JNI methods there.
             */
            nativeMediaProcessor = vm.resolveClass(NATIVE_CLASS_NAME);
            File library = resolveLibraryFile();
            DalvikModule dalvikModule = vm.loadLibrary(library, false);
            module = dalvikModule.getModule();
            dalvikModule.callJNI_OnLoad(emulator);
            initializationNanos = System.nanoTime() - initializationStarted;
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

    public String getBackendName() {
        return emulator.getBackend().getClass().getSimpleName();
    }

    public long getInitializationNanos() {
        return initializationNanos;
    }

    /**
     * Supplies the configured IMEI when native code calls Android telephony
     * APIs through JNI. IMEI is not an Android system property in unidbg.
     */
    @Override
    public DvmObject<?> callObjectMethod(
            BaseVM currentVm,
            DvmObject<?> object,
            String signature,
            VarArg arguments) {
        if (isImeiMethod(signature)) {
            return new StringObject(currentVm, imei);
        }
        return super.callObjectMethod(
                currentVm, object, signature, arguments);
    }

    @Override
    public DvmObject<?> callObjectMethodV(
            BaseVM currentVm,
            DvmObject<?> object,
            String signature,
            VaList arguments) {
        if (isImeiMethod(signature)) {
            return new StringObject(currentVm, imei);
        }
        return super.callObjectMethodV(
                currentVm, object, signature, arguments);
    }

    private boolean isImeiMethod(String signature) {
        return "android/telephony/TelephonyManager->getDeviceId()Ljava/lang/String;"
                .equals(signature)
                || "android/telephony/TelephonyManager->getDeviceId(I)Ljava/lang/String;"
                .equals(signature)
                || "android/telephony/TelephonyManager->getImei()Ljava/lang/String;"
                .equals(signature)
                || "android/telephony/TelephonyManager->getImei(I)Ljava/lang/String;"
                .equals(signature);
    }

    private void configureBackend(AndroidEmulatorBuilder builder) {
        String requestedBackend = System.getProperty(
                BACKEND_PROPERTY, "dynarmic").trim();
        if ("dynarmic".equalsIgnoreCase(requestedBackend)) {
            /*
             * Prefer Dynarmic JIT and fall back to Unicorn2 only when its
             * native backend cannot load.
             */
            builder.addBackendFactory(new DynarmicFactory(true));
            builder.addBackendFactory(new Unicorn2Factory(false));
            return;
        }
        if ("unicorn2".equalsIgnoreCase(requestedBackend)) {
            builder.addBackendFactory(new Unicorn2Factory(false));
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported emulator backend '" + requestedBackend
                        + "'. Use dynarmic or unicorn2.");
    }

    /**
     * Calls the method associated with the emulated class by RegisterNatives.
     */
    public synchronized String getDynamicJniVersion() {
        StringObject result = (StringObject) nativeMediaProcessor
                .callStaticJniMethodObject(
                        emulator,
                        "nativeVersion()Ljava/lang/String;");
        if (result == null) {
            throw new IllegalStateException(
                    "Custom native library returned no JNI version");
        }
        return result.getValue();
    }

    /**
     * Calls the library's plain exported C API directly by symbol name.
     *
     * <p>This path has no Java class or JNI method signature.</p>
     */
    public synchronized String getSystemLibraryVersion() {
        Symbol symbol = module.findSymbolByName(
                "so_signer_native_version");
        if (symbol == null) {
            throw new IllegalStateException(
                    "Custom native version symbol was not exported");
        }
        Number address = symbol.call(emulator);
        UnidbgPointer value = UnidbgPointer.pointer(emulator, address);
        if (value == null) {
            throw new IllegalStateException(
                    "Custom native version symbol returned null");
        }
        return value.getString(0);
    }

    /**
     * Calculates the file CRC and appends it through dynamically registered
     * JNI.
     */
    public synchronized int appendFileCrc32WithDynamicJni(
            Path output) throws IOException {
        mapOutput(output);
        try {
            return nativeMediaProcessor.callStaticJniMethodInt(
                    emulator,
                    "appendFileCrc32(Ljava/lang/String;)I",
                    new StringObject(vm, VIRTUAL_OUTPUT_PATH));
        } finally {
            mappedOutput = null;
        }
    }

    /**
     * Calculates the file CRC and appends it through the plain exported C
     * symbol.
     */
    public synchronized int appendFileCrc32WithSystemLibrary(
            Path output) throws IOException {
        mapOutput(output);
        MemoryBlock outputPath = emulator.getMemory().malloc(
                VIRTUAL_OUTPUT_PATH.length() + 1, true);
        try {
            outputPath.getPointer().setString(0, VIRTUAL_OUTPUT_PATH);
            return module.callFunction(
                    emulator,
                    "so_signer_append_file_crc32",
                    outputPath.getPointer()).intValue();
        } finally {
            outputPath.free();
            mappedOutput = null;
        }
    }

    /**
     * Resolves only the output path exposed to the native processor. Returning
     * null delegates Android system paths to unidbg's normal resolvers.
     */
    @Override
    public FileResult<AndroidFileIO> resolve(
            Emulator<AndroidFileIO> currentEmulator,
            String path,
            int openFlags) {
        if (VIRTUAL_OUTPUT_PATH.equals(path) && mappedOutput != null) {
            return FileResult.success(new SimpleFileIO(
                    openFlags, mappedOutput.toFile(), path));
        }
        return null;
    }

    private void mapOutput(Path output) throws IOException {
        Path absoluteOutput = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteOutput)) {
            throw new FileNotFoundException(
                    "Native output file not found: " + absoluteOutput);
        }
        mappedOutput = absoluteOutput;
    }

    private File resolveLibraryFile() throws IOException {
        InputStream resource = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CLASSPATH_LIBRARY);
        if (resource == null) {
            throw new FileNotFoundException(
                    "Custom Android library not found at "
                            + CLASSPATH_LIBRARY
                            + ". Run native/build.ps1 first.");
        }

        extractedLibrary = Files.createTempFile(
                "so-signer-native-", ".so");
        extractedLibrary.toFile().deleteOnExit();
        try (InputStream input = resource) {
            Files.copy(input, extractedLibrary,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return extractedLibrary.toFile();
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
            module = null;
        }
        if (extractedLibrary != null) {
            try {
                Files.deleteIfExists(extractedLibrary);
            } catch (IOException ignored) {
                /*
                 * Windows may retain the mapped file until JVM termination;
                 * deleteOnExit() was registered when the file was created.
                 */
            }
            extractedLibrary = null;
        }
    }
}
