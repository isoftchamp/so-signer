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
import com.github.unidbg.linux.android.dvm.ArrayListObject;
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
import java.util.ArrayList;
import java.util.List;

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
    private static final int SYSTEM_LIBRARY_API_LEVEL = 23;
    private static final String TELEPHONY_SERVICE = "phone";
    private static final String TELEPHONY_MANAGER_CLASS =
            "android/telephony/TelephonyManager";
    private static final String SUBSCRIPTION_SERVICE =
            "telephony_subscription_service";
    private static final String SUBSCRIPTION_MANAGER_CLASS =
            "android/telephony/SubscriptionManager";
    private static final String SUBSCRIPTION_INFO_CLASS =
            "android/telephony/SubscriptionInfo";
    private static final String READ_PHONE_STATE =
            "android.permission.READ_PHONE_STATE";
    private static final String READ_PRIVILEGED_PHONE_STATE =
            "android.permission.READ_PRIVILEGED_PHONE_STATE";

    private final TelephonyProfile telephonyProfile;
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
    public AndroidSoRuntime(TelephonyProfile telephonyProfile)
            throws IOException {
        this.telephonyProfile = telephonyProfile;
        long initializationStarted = System.nanoTime();
        try {
            AndroidEmulatorBuilder builder =
                    AndroidEmulatorBuilder.for32Bit();
            builder.setProcessName("com.example.signer.so");
            configureBackend(builder);
            emulator = builder.build();
            emulator.getMemory().setLibraryResolver(
                    new AndroidResolver(SYSTEM_LIBRARY_API_LEVEL));
            emulator.getSyscallHandler().addIOResolver(this);

            vm = emulator.createDalvikVM();
            vm.setVerbose(Boolean.getBoolean("so.signer.jni.verbose"));
            vm.setJni(this);

            /*
             * Resolve the synthetic class before loading so unidbg can bind
             * its exported Java_* JNI entry points.
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

    @Override
    public DvmObject<?> getStaticObjectField(
            BaseVM currentVm,
            DvmClass dvmClass,
            String signature) {
        if ("android/content/Context->TELEPHONY_SERVICE:Ljava/lang/String;"
                .equals(signature)) {
            return new StringObject(currentVm, TELEPHONY_SERVICE);
        }
        if ("android/content/Context->TELEPHONY_SUBSCRIPTION_SERVICE:"
                .concat("Ljava/lang/String;").equals(signature)) {
            return new StringObject(currentVm, SUBSCRIPTION_SERVICE);
        }
        return super.getStaticObjectField(currentVm, dvmClass, signature);
    }

    @Override
    public DvmObject<?> callObjectMethod(
            BaseVM currentVm,
            DvmObject<?> object,
            String signature,
            VarArg arguments) {
        DvmObject<?> telephonyObject = handleTelephonyObjectMethod(
                currentVm, object, signature, arguments);
        if (telephonyObject != TelephonyDispatch.NOT_HANDLED) {
            return telephonyObject;
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
        DvmObject<?> telephonyObject = handleTelephonyObjectMethod(
                currentVm, object, signature, arguments);
        if (telephonyObject != TelephonyDispatch.NOT_HANDLED) {
            return telephonyObject;
        }
        return super.callObjectMethodV(
                currentVm, object, signature, arguments);
    }

    @Override
    public DvmObject<?> callStaticObjectMethod(
            BaseVM currentVm,
            DvmClass dvmClass,
            String signature,
            VarArg arguments) {
        DvmObject<?> result = handleStaticTelephonyObjectMethod(
                signature, arguments);
        return result == TelephonyDispatch.NOT_HANDLED
                ? super.callStaticObjectMethod(
                        currentVm, dvmClass, signature, arguments)
                : result;
    }

    @Override
    public DvmObject<?> callStaticObjectMethodV(
            BaseVM currentVm,
            DvmClass dvmClass,
            String signature,
            VaList arguments) {
        DvmObject<?> result = handleStaticTelephonyObjectMethod(
                signature, arguments);
        return result == TelephonyDispatch.NOT_HANDLED
                ? super.callStaticObjectMethodV(
                        currentVm, dvmClass, signature, arguments)
                : result;
    }

    @Override
    public int callIntMethod(
            BaseVM currentVm,
            DvmObject<?> object,
            String signature,
            VarArg arguments) {
        Integer result = handleTelephonyIntMethod(
                object, signature, arguments);
        return result == null
                ? super.callIntMethod(currentVm, object, signature, arguments)
                : result;
    }

    @Override
    public int callIntMethodV(
            BaseVM currentVm,
            DvmObject<?> object,
            String signature,
            VaList arguments) {
        Integer result = handleTelephonyIntMethod(
                object, signature, arguments);
        return result == null
                ? super.callIntMethodV(currentVm, object, signature, arguments)
                : result;
    }

    @Override
    public int callStaticIntMethod(
            BaseVM currentVm,
            DvmClass dvmClass,
            String signature,
            VarArg arguments) {
        Integer result = handleStaticTelephonyIntMethod(
                signature, arguments);
        return result == null
                ? super.callStaticIntMethod(
                        currentVm, dvmClass, signature, arguments)
                : result;
    }

    @Override
    public int callStaticIntMethodV(
            BaseVM currentVm,
            DvmClass dvmClass,
            String signature,
            VaList arguments) {
        Integer result = handleStaticTelephonyIntMethod(
                signature, arguments);
        return result == null
                ? super.callStaticIntMethodV(
                        currentVm, dvmClass, signature, arguments)
                : result;
    }

    @Override
    public boolean callStaticBooleanMethod(
            BaseVM currentVm,
            DvmClass dvmClass,
            String signature,
            VarArg arguments) {
        Boolean result = handleStaticTelephonyBooleanMethod(
                signature, arguments);
        return result == null
                ? super.callStaticBooleanMethod(
                        currentVm, dvmClass, signature, arguments)
                : result;
    }

    @Override
    public boolean callStaticBooleanMethodV(
            BaseVM currentVm,
            DvmClass dvmClass,
            String signature,
            VaList arguments) {
        Boolean result = handleStaticTelephonyBooleanMethod(
                signature, arguments);
        return result == null
                ? super.callStaticBooleanMethodV(
                        currentVm, dvmClass, signature, arguments)
                : result;
    }

    private DvmObject<?> handleTelephonyObjectMethod(
            BaseVM currentVm,
            DvmObject<?> object,
            String signature,
            VarArg arguments) {
        if (isSystemServiceMethod(signature)) {
            return resolveSystemService(arguments);
        }
        if ("android/telephony/TelephonyManager->createForSubscriptionId(I)"
                .concat("Landroid/telephony/TelephonyManager;")
                .equals(signature)) {
            return createTelephonyManager(arguments.getIntArg(0));
        }
        if ("android/telephony/SubscriptionManager"
                .concat("->getActiveSubscriptionInfoList()Ljava/util/List;")
                .equals(signature)) {
            enforcePhoneStatePermission();
            return new ArrayListObject(vm, createSubscriptionInfoObjects());
        }
        if ("android/telephony/SubscriptionManager"
                .concat("->getActiveSubscriptionInfoForSimSlotIndex(I)")
                .concat("Landroid/telephony/SubscriptionInfo;")
                .equals(signature)) {
            enforcePhoneStatePermission();
            int subscriptionId = telephonyProfile.getSubscriptionIdForSlot(
                    arguments.getIntArg(0));
            return subscriptionId < 0
                    ? null : createSubscriptionInfo(subscriptionId);
        }
        if ("android/telephony/SubscriptionManager"
                .concat("->getActiveSubscriptionInfo(I)")
                .concat("Landroid/telephony/SubscriptionInfo;")
                .equals(signature)) {
            enforcePhoneStatePermission();
            int subscriptionId = arguments.getIntArg(0);
            return telephonyProfile.getSlotIndexForSubscription(
                    subscriptionId) < 0
                    ? null : createSubscriptionInfo(subscriptionId);
        }
        if (isImeiMethod(signature)) {
            enforcePhoneStatePermission();
            String imei = resolveImei(object, signature, arguments);
            return imei == null ? null : new StringObject(currentVm, imei);
        }
        return TelephonyDispatch.NOT_HANDLED;
    }

    private DvmObject<?> handleStaticTelephonyObjectMethod(
            String signature,
            VarArg arguments) {
        if ("android/telephony/TelephonyManager->getDefault()"
                .concat("Landroid/telephony/TelephonyManager;")
                .equals(signature)) {
            return createTelephonyManager(
                    telephonyProfile.getDefaultSubscriptionId());
        }
        if ("android/telephony/SubscriptionManager"
                .concat("->from(Landroid/content/Context;)")
                .concat("Landroid/telephony/SubscriptionManager;")
                .equals(signature)) {
            return createSubscriptionManager();
        }
        return TelephonyDispatch.NOT_HANDLED;
    }

    private Integer handleTelephonyIntMethod(
            DvmObject<?> object,
            String signature,
            VarArg arguments) {
        if ("android/telephony/TelephonyManager->getPhoneCount()I"
                .equals(signature)) {
            return telephonyProfile.getSlotCount();
        }
        if ("android/telephony/SubscriptionManager"
                .concat("->getActiveSubscriptionInfoCount()I")
                .equals(signature)
                || "android/telephony/SubscriptionManager"
                .concat("->getActiveSubscriptionInfoCountMax()I")
                .equals(signature)) {
            enforcePhoneStatePermission();
            return telephonyProfile.getSlotCount();
        }
        Object value = object == null ? null : object.getValue();
        if (value instanceof SubscriptionInfoState) {
            SubscriptionInfoState info = (SubscriptionInfoState) value;
            if ("android/telephony/SubscriptionInfo->getSubscriptionId()I"
                    .equals(signature)) {
                return info.subscriptionId;
            }
            if ("android/telephony/SubscriptionInfo->getSimSlotIndex()I"
                    .equals(signature)) {
                return info.slotIndex;
            }
        }
        if (signature.endsWith(
                "->checkSelfPermission(Ljava/lang/String;)I")
                || signature.endsWith(
                        "->checkCallingOrSelfPermission(Ljava/lang/String;)I")
                || signature.endsWith(
                        "->checkCallingPermission(Ljava/lang/String;)I")) {
            DvmObject<?> permission = arguments.getObjectArg(0);
            if (permission instanceof StringObject
                    && isPhoneStatePermission(
                            ((StringObject) permission).getValue())) {
                return telephonyProfile.isPhoneStatePermissionGranted()
                        ? 0 : -1;
            }
        }
        return null;
    }

    private Integer handleStaticTelephonyIntMethod(
            String signature,
            VarArg arguments) {
        if (signature.endsWith(
                "ContextCompat->checkSelfPermission("
                        + "Landroid/content/Context;Ljava/lang/String;)I")) {
            DvmObject<?> permission = arguments.getObjectArg(1);
            if (permission instanceof StringObject
                    && isPhoneStatePermission(
                            ((StringObject) permission).getValue())) {
                return telephonyProfile.isPhoneStatePermissionGranted()
                        ? 0 : -1;
            }
        }
        if ("android/telephony/SubscriptionManager"
                .concat("->getDefaultSubscriptionId()I")
                .equals(signature)
                || "android/telephony/SubscriptionManager"
                .concat("->getDefaultDataSubscriptionId()I")
                .equals(signature)
                || "android/telephony/SubscriptionManager"
                .concat("->getDefaultSmsSubscriptionId()I")
                .equals(signature)
                || "android/telephony/SubscriptionManager"
                .concat("->getDefaultVoiceSubscriptionId()I")
                .equals(signature)
                || "android/telephony/SubscriptionManager->getDefaultSubId()I"
                .equals(signature)) {
            return telephonyProfile.getDefaultSubscriptionId();
        }
        return null;
    }

    private Boolean handleStaticTelephonyBooleanMethod(
            String signature,
            VarArg arguments) {
        if ("android/telephony/SubscriptionManager"
                .concat("->isValidSubscriptionId(I)Z")
                .equals(signature)) {
            return telephonyProfile.getSlotIndexForSubscription(
                    arguments.getIntArg(0)) >= 0;
        }
        if ("android/telephony/SubscriptionManager"
                .concat("->isValidSlotIndex(I)Z")
                .equals(signature)) {
            int slotIndex = arguments.getIntArg(0);
            return slotIndex >= 0
                    && slotIndex < telephonyProfile.getSlotCount();
        }
        return null;
    }

    private DvmObject<?> resolveSystemService(VarArg arguments) {
        DvmObject<?> service = arguments.getObjectArg(0);
        if (service instanceof StringObject) {
            String serviceName = ((StringObject) service).getValue();
            if (TELEPHONY_SERVICE.equals(serviceName)) {
                return createTelephonyManager(
                        telephonyProfile.getDefaultSubscriptionId());
            }
            if (SUBSCRIPTION_SERVICE.equals(serviceName)) {
                return createSubscriptionManager();
            }
            return TelephonyDispatch.NOT_HANDLED;
        }
        if (service instanceof DvmClass) {
            String className = ((DvmClass) service).getClassName();
            if (TELEPHONY_MANAGER_CLASS.equals(className)) {
                return createTelephonyManager(
                        telephonyProfile.getDefaultSubscriptionId());
            }
            if (SUBSCRIPTION_MANAGER_CLASS.equals(className)) {
                return createSubscriptionManager();
            }
            return TelephonyDispatch.NOT_HANDLED;
        }
        return TelephonyDispatch.NOT_HANDLED;
    }

    private DvmObject<?> createTelephonyManager(int subscriptionId) {
        return vm.resolveClass(TELEPHONY_MANAGER_CLASS).newObject(
                new TelephonyManagerState(subscriptionId));
    }

    private DvmObject<?> createSubscriptionManager() {
        return vm.resolveClass(SUBSCRIPTION_MANAGER_CLASS)
                .newObject(telephonyProfile);
    }

    private List<DvmObject<?>> createSubscriptionInfoObjects() {
        List<DvmObject<?>> subscriptions = new ArrayList<>();
        for (int subscriptionId
                : telephonyProfile.getActiveSubscriptionIds()) {
            subscriptions.add(createSubscriptionInfo(subscriptionId));
        }
        return subscriptions;
    }

    private DvmObject<?> createSubscriptionInfo(int subscriptionId) {
        int slotIndex = telephonyProfile.getSlotIndexForSubscription(
                subscriptionId);
        return vm.resolveClass(SUBSCRIPTION_INFO_CLASS).newObject(
                new SubscriptionInfoState(subscriptionId, slotIndex));
    }

    private String resolveImei(
            DvmObject<?> object,
            String signature,
            VarArg arguments) {
        if (signature.contains("(I)")) {
            return telephonyProfile.getImeiForSlot(arguments.getIntArg(0));
        }
        Object value = object == null ? null : object.getValue();
        if (value instanceof TelephonyManagerState) {
            return telephonyProfile.getImeiForSubscription(
                    ((TelephonyManagerState) value).subscriptionId);
        }
        return telephonyProfile.getImeiForDefaultSubscription();
    }

    private void enforcePhoneStatePermission() {
        if (!telephonyProfile.isPhoneStatePermissionGranted()) {
            throw new SecurityException(
                    "READ_PHONE_STATE is required to access IMEI");
        }
    }

    private boolean isSystemServiceMethod(String signature) {
        return signature.endsWith(
                "->getSystemService(Ljava/lang/String;)Ljava/lang/Object;")
                || signature.endsWith(
                        "->getSystemService(Ljava/lang/Class;)Ljava/lang/Object;");
    }

    private boolean isPhoneStatePermission(String permission) {
        return READ_PHONE_STATE.equals(permission)
                || READ_PRIVILEGED_PHONE_STATE.equals(permission);
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

    private static final class TelephonyManagerState {

        private final int subscriptionId;

        private TelephonyManagerState(int subscriptionId) {
            this.subscriptionId = subscriptionId;
        }
    }

    private static final class SubscriptionInfoState {

        private final int subscriptionId;
        private final int slotIndex;

        private SubscriptionInfoState(int subscriptionId, int slotIndex) {
            this.subscriptionId = subscriptionId;
            this.slotIndex = slotIndex;
        }
    }

    /**
     * A non-null sentinel is needed because a handled Android getter may
     * legitimately return null for an invalid or unavailable SIM slot.
     */
    private static final class TelephonyDispatch {

        private static final DvmObject<?> NOT_HANDLED =
                new DvmObject<Object>(null, null) {
                };

        private TelephonyDispatch() {
        }
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
     * Calls the library version method through its exported JNI entry point.
     */
    public synchronized String getJniVersion() {
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
     * Runs an end-to-end native JNI probe through Context, TelephonyManager,
     * permissions, SubscriptionManager, and SubscriptionInfo.
     */
    public synchronized String verifyTelephonyIntegration() {
        String expectedImei =
                telephonyProfile.getImeiForDefaultSubscription();
        DvmObject<?> context = vm.resolveClass("android/content/Context")
                .newObject(null);
        StringObject result = (StringObject) nativeMediaProcessor
                .callStaticJniMethodObject(
                        emulator,
                        "probeTelephony(Ljava/lang/Object;Ljava/lang/String;)"
                                + "Ljava/lang/String;",
                        context,
                        new StringObject(vm, expectedImei));
        String expectedResult = "OK:" + expectedImei;
        if (result == null || !expectedResult.equals(result.getValue())) {
            throw new IllegalStateException(
                    "Android telephony integration probe failed: "
                            + (result == null ? "no result" : result.getValue()));
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
     * Calculates the file CRC and appends it through JNI.
     */
    public synchronized int appendFileCrc32WithJni(
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
