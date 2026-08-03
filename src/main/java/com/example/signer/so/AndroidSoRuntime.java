package com.example.signer.so;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VM;
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
public final class AndroidSoRuntime extends AbstractJni implements AutoCloseable {

    public static final String LIBRARY_FILE_NAME =
            "libso-signer-native.so";

    private static final String CLASSPATH_LIBRARY =
            "native/" + LIBRARY_FILE_NAME;

    private AndroidEmulator emulator;
    private VM vm;
    private DvmClass nativeMediaProcessor;
    private Module module;
    private Path extractedLibrary;

    /**
     * Starts a 32-bit Android VM and calls the library's JNI_OnLoad function.
     *
     * <p>The custom library is currently built for ARM32. A future ARM64
     * build must use {@code for64Bit()} and a matching native resource.</p>
     */
    public AndroidSoRuntime() throws IOException {
        try {
            emulator = AndroidEmulatorBuilder.for32Bit()
                    .setProcessName("com.example.signer.so")
                    .addBackendFactory(new Unicorn2Factory(true))
                    .build();
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));

            vm = emulator.createDalvikVM();
            vm.setVerbose(false);
            vm.setJni(this);

            /*
             * Resolve the synthetic class before JNI_OnLoad because the
             * library dynamically registers its JNI methods there.
             */
            nativeMediaProcessor = vm.resolveClass(
                    "com/example/signer/so/NativeMediaProcessor");
            File library = resolveLibraryFile();
            DalvikModule dalvikModule = vm.loadLibrary(library, false);
            module = dalvikModule.getModule();
            dalvikModule.callJNI_OnLoad(emulator);
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
