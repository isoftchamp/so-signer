#include <jni.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <new>

extern "C" JNIEXPORT int so_signer_append_file_crc32(
        const char* output_path);
extern "C" JNIEXPORT int so_signer_append_file_crc32_with_imei(
        const char* output_path,
        const char* imei);

namespace {

constexpr char kNativeVersion[] = "so-signer-native/4";
constexpr std::size_t kReadBufferSize = 1024 * 1024;
constexpr std::uint32_t kCrc32cPolynomial = 0x82f63b78U;
constexpr std::size_t kImeiLength = 15;
constexpr std::array<unsigned char, 4> kImeiSeparator = {
    'I', 'M', 'E', 'I'
};
constexpr std::array<unsigned char, 4> kHashSeparator = {
    'H', 'A', 'S', 'H'
};

enum AppendStatus {
    kSuccess = 0,
    kInvalidArgument = 1,
    kInputOpenFailed = 2,
    kBufferAllocationFailed = 3,
    kInputReadFailed = 4,
    kInputCloseFailed = 5,
    kOutputOpenFailed = 6,
    kSeparatorWriteFailed = 7,
    kChecksumWriteFailed = 8,
    kOutputCloseFailed = 9,
    kImeiSeparatorWriteFailed = 10,
    kImeiWriteFailed = 11
};

using CrcTable = std::array<
        std::array<std::uint32_t, 256>, 8>;

/**
 * Builds slicing-by-8 lookup tables for CRC-32C. Processing eight bytes per
 * iteration is substantially faster under ARM emulation than bit-at-a-time
 * or byte-at-a-time CRC code.
 */
constexpr CrcTable CreateCrc32cTable() {
    CrcTable table{};
    for (std::size_t index = 0; index < table[0].size(); ++index) {
        std::uint32_t value = static_cast<std::uint32_t>(index);
        for (int bit = 0; bit < 8; ++bit) {
            value = (value >> 1U)
                    ^ ((value & 1U) == 0U ? 0U : kCrc32cPolynomial);
        }
        table[0][index] = value;
    }
    for (std::size_t slice = 1; slice < table.size(); ++slice) {
        for (std::size_t index = 0; index < table[slice].size(); ++index) {
            const std::uint32_t previous = table[slice - 1][index];
            table[slice][index] = (previous >> 8U)
                    ^ table[0][previous & 0xffU];
        }
    }
    return table;
}

constexpr CrcTable kCrc32cTable = CreateCrc32cTable();

std::uint32_t UpdateCrc32c(
        std::uint32_t crc,
        const unsigned char* data,
        std::size_t length) {
    while (length >= 8) {
        crc ^= static_cast<std::uint32_t>(data[0])
                | (static_cast<std::uint32_t>(data[1]) << 8U)
                | (static_cast<std::uint32_t>(data[2]) << 16U)
                | (static_cast<std::uint32_t>(data[3]) << 24U);
        crc = kCrc32cTable[7][crc & 0xffU]
                ^ kCrc32cTable[6][(crc >> 8U) & 0xffU]
                ^ kCrc32cTable[5][(crc >> 16U) & 0xffU]
                ^ kCrc32cTable[4][(crc >> 24U) & 0xffU]
                ^ kCrc32cTable[3][data[4]]
                ^ kCrc32cTable[2][data[5]]
                ^ kCrc32cTable[1][data[6]]
                ^ kCrc32cTable[0][data[7]];
        data += 8;
        length -= 8;
    }
    while (length > 0) {
        crc = (crc >> 8U) ^ kCrc32cTable[0][
                (crc ^ *data) & 0xffU];
        ++data;
        --length;
    }
    return crc;
}

jstring nativeVersion(JNIEnv* environment, jclass /* type */) {
    return environment->NewStringUTF(kNativeVersion);
}

jstring ProbeFailure(JNIEnv* environment, const char* step) {
    return environment->NewStringUTF(step);
}

bool SameString(
        JNIEnv* environment,
        jstring actual,
        jstring expected) {
    if (actual == nullptr || expected == nullptr) {
        return actual == expected;
    }
    const char* actual_chars = environment->GetStringUTFChars(
            actual, nullptr);
    const char* expected_chars = environment->GetStringUTFChars(
            expected, nullptr);
    if (actual_chars == nullptr || expected_chars == nullptr) {
        if (actual_chars != nullptr) {
            environment->ReleaseStringUTFChars(actual, actual_chars);
        }
        if (expected_chars != nullptr) {
            environment->ReleaseStringUTFChars(expected, expected_chars);
        }
        return false;
    }
    std::size_t index = 0;
    while (actual_chars[index] != '\0'
            && actual_chars[index] == expected_chars[index]) {
        ++index;
    }
    const bool equal = actual_chars[index] == expected_chars[index];
    environment->ReleaseStringUTFChars(actual, actual_chars);
    environment->ReleaseStringUTFChars(expected, expected_chars);
    return equal;
}

/**
 * Exercises the Android Java IMEI acquisition path from native JNI. Keeping
 * this probe in the test library verifies the same FindClass/GetMethodID and
 * Call*Method flow used by a production Android library.
 */
jstring probeTelephony(
        JNIEnv* environment,
        jclass /* type */,
        jobject context,
        jstring expected_imei) {
    if (context == nullptr || expected_imei == nullptr) {
        return ProbeFailure(environment, "ERROR:arguments");
    }

    jclass context_class = environment->FindClass(
            "android/content/Context");
    jfieldID telephony_service_field = environment->GetStaticFieldID(
            context_class,
            "TELEPHONY_SERVICE",
            "Ljava/lang/String;");
    jstring telephony_service = static_cast<jstring>(
            environment->GetStaticObjectField(
                    context_class, telephony_service_field));
    jmethodID get_system_service = environment->GetMethodID(
            context_class,
            "getSystemService",
            "(Ljava/lang/String;)Ljava/lang/Object;");
    jvalue service_arguments[1]{};
    service_arguments[0].l = telephony_service;
    jobject telephony_manager = environment->CallObjectMethodA(
            context, get_system_service, service_arguments);
    if (telephony_manager == nullptr) {
        return ProbeFailure(environment, "ERROR:telephony-service");
    }

    jstring permission = environment->NewStringUTF(
            "android.permission.READ_PHONE_STATE");
    jmethodID check_permission = environment->GetMethodID(
            context_class,
            "checkSelfPermission",
            "(Ljava/lang/String;)I");
    jvalue permission_arguments[1]{};
    permission_arguments[0].l = permission;
    if (environment->CallIntMethodA(
            context, check_permission, permission_arguments) != 0) {
        return ProbeFailure(environment, "ERROR:permission");
    }

    jclass telephony_class = environment->FindClass(
            "android/telephony/TelephonyManager");
    jmethodID get_imei = environment->GetMethodID(
            telephony_class,
            "getImei",
            "()Ljava/lang/String;");
    jmethodID get_imei_for_slot = environment->GetMethodID(
            telephony_class,
            "getImei",
            "(I)Ljava/lang/String;");
    jmethodID get_device_id = environment->GetMethodID(
            telephony_class,
            "getDeviceId",
            "()Ljava/lang/String;");
    jmethodID get_device_id_for_slot = environment->GetMethodID(
            telephony_class,
            "getDeviceId",
            "(I)Ljava/lang/String;");
    jmethodID get_phone_count = environment->GetMethodID(
            telephony_class,
            "getPhoneCount",
            "()I");
    jmethodID create_for_subscription = environment->GetMethodID(
            telephony_class,
            "createForSubscriptionId",
            "(I)Landroid/telephony/TelephonyManager;");

    if (!SameString(
            environment,
            static_cast<jstring>(environment->CallObjectMethodA(
                    telephony_manager, get_imei, nullptr)),
            expected_imei)) {
        return ProbeFailure(environment, "ERROR:getImei");
    }
    jvalue slot_zero_arguments[1]{};
    slot_zero_arguments[0].i = 0;
    if (!SameString(
            environment,
            static_cast<jstring>(environment->CallObjectMethodA(
                    telephony_manager,
                    get_imei_for_slot,
                    slot_zero_arguments)),
            expected_imei)) {
        return ProbeFailure(environment, "ERROR:getImei-slot0");
    }
    jvalue slot_one_arguments[1]{};
    slot_one_arguments[0].i = 1;
    if (environment->CallObjectMethodA(
            telephony_manager,
            get_imei_for_slot,
            slot_one_arguments) != nullptr) {
        return ProbeFailure(environment, "ERROR:getImei-invalid-slot");
    }
    if (!SameString(
            environment,
            static_cast<jstring>(environment->CallObjectMethodA(
                    telephony_manager, get_device_id, nullptr)),
            expected_imei)
            || !SameString(
                    environment,
                    static_cast<jstring>(environment->CallObjectMethodA(
                            telephony_manager,
                            get_device_id_for_slot,
                            slot_zero_arguments)),
                    expected_imei)) {
        return ProbeFailure(environment, "ERROR:getDeviceId");
    }
    if (environment->CallIntMethodA(
            telephony_manager, get_phone_count, nullptr) != 1) {
        return ProbeFailure(environment, "ERROR:phone-count");
    }

    jclass subscription_class = environment->FindClass(
            "android/telephony/SubscriptionManager");
    jmethodID subscription_from = environment->GetStaticMethodID(
            subscription_class,
            "from",
            "(Landroid/content/Context;)"
            "Landroid/telephony/SubscriptionManager;");
    jvalue context_arguments[1]{};
    context_arguments[0].l = context;
    jobject subscription_manager =
            environment->CallStaticObjectMethodA(
                    subscription_class,
                    subscription_from,
                    context_arguments);
    if (subscription_manager == nullptr) {
        return ProbeFailure(environment, "ERROR:subscription-service");
    }

    jmethodID get_default_subscription = environment->GetStaticMethodID(
            subscription_class,
            "getDefaultSubscriptionId",
            "()I");
    const jint default_subscription =
            environment->CallStaticIntMethod(
                    subscription_class,
                    get_default_subscription);
    if (default_subscription != 1) {
        return ProbeFailure(environment, "ERROR:default-subscription");
    }

    jmethodID get_active_count = environment->GetMethodID(
            subscription_class,
            "getActiveSubscriptionInfoCount",
            "()I");
    if (environment->CallIntMethodA(
            subscription_manager, get_active_count, nullptr) != 1) {
        return ProbeFailure(environment, "ERROR:subscription-count");
    }

    jmethodID get_info_for_slot = environment->GetMethodID(
            subscription_class,
            "getActiveSubscriptionInfoForSimSlotIndex",
            "(I)Landroid/telephony/SubscriptionInfo;");
    jobject subscription_info = environment->CallObjectMethodA(
            subscription_manager,
            get_info_for_slot,
            slot_zero_arguments);
    if (subscription_info == nullptr) {
        return ProbeFailure(environment, "ERROR:subscription-info");
    }
    jclass subscription_info_class = environment->FindClass(
            "android/telephony/SubscriptionInfo");
    jmethodID get_subscription_id = environment->GetMethodID(
            subscription_info_class,
            "getSubscriptionId",
            "()I");
    jmethodID get_slot_index = environment->GetMethodID(
            subscription_info_class,
            "getSimSlotIndex",
            "()I");
    if (environment->CallIntMethodA(
            subscription_info, get_subscription_id, nullptr) != 1
            || environment->CallIntMethodA(
                    subscription_info, get_slot_index, nullptr) != 0) {
        return ProbeFailure(environment, "ERROR:subscription-routing");
    }

    jvalue subscription_arguments[1]{};
    subscription_arguments[0].i = default_subscription;
    jobject subscription_telephony = environment->CallObjectMethodA(
            telephony_manager,
            create_for_subscription,
            subscription_arguments);
    if (!SameString(
            environment,
            static_cast<jstring>(environment->CallObjectMethodA(
                    subscription_telephony, get_imei, nullptr)),
            expected_imei)) {
        return ProbeFailure(environment, "ERROR:subscription-imei");
    }

    const char* expected_chars = environment->GetStringUTFChars(
            expected_imei, nullptr);
    if (expected_chars == nullptr) {
        return ProbeFailure(environment, "ERROR:result");
    }
    std::array<char, 32> result{};
    result[0] = 'O';
    result[1] = 'K';
    result[2] = ':';
    std::size_t result_index = 3;
    while (expected_chars[result_index - 3] != '\0'
            && result_index + 1 < result.size()) {
        result[result_index] = expected_chars[result_index - 3];
        ++result_index;
    }
    environment->ReleaseStringUTFChars(expected_imei, expected_chars);
    return environment->NewStringUTF(result.data());
}

bool IsValidImei(const char* imei) {
    if (imei == nullptr) {
        return false;
    }
    for (std::size_t index = 0; index < kImeiLength; ++index) {
        if (imei[index] < '0' || imei[index] > '9') {
            return false;
        }
    }
    return imei[kImeiLength] == '\0';
}

jstring getDefaultDeviceId(JNIEnv* environment) {
    jclass telephony_class = environment->FindClass(
            "android/telephony/TelephonyManager");
    if (telephony_class == nullptr) {
        return nullptr;
    }
    jmethodID get_default = environment->GetStaticMethodID(
            telephony_class,
            "getDefault",
            "()Landroid/telephony/TelephonyManager;");
    jmethodID get_device_id = environment->GetMethodID(
            telephony_class,
            "getDeviceId",
            "()Ljava/lang/String;");
    if (get_default == nullptr || get_device_id == nullptr) {
        return nullptr;
    }
    jobject telephony_manager = environment->CallStaticObjectMethodA(
            telephony_class, get_default, nullptr);
    if (telephony_manager == nullptr) {
        return nullptr;
    }
    return static_cast<jstring>(environment->CallObjectMethodA(
            telephony_manager, get_device_id, nullptr));
}

jint appendFileCrc32(
        JNIEnv* environment,
        jclass /* type */,
        jstring output_path) {
    if (output_path == nullptr) {
        return kInvalidArgument;
    }

    const char* output = environment->GetStringUTFChars(
            output_path, nullptr);
    if (output == nullptr) {
        return kInvalidArgument;
    }

    jstring imei_string = getDefaultDeviceId(environment);
    if (imei_string == nullptr) {
        environment->ReleaseStringUTFChars(output_path, output);
        return kInvalidArgument;
    }
    const char* imei = environment->GetStringUTFChars(
            imei_string, nullptr);
    if (!IsValidImei(imei)) {
        if (imei != nullptr) {
            environment->ReleaseStringUTFChars(imei_string, imei);
        }
        environment->ReleaseStringUTFChars(output_path, output);
        return kInvalidArgument;
    }

    const int status = so_signer_append_file_crc32_with_imei(
            output, imei);
    environment->ReleaseStringUTFChars(imei_string, imei);
    environment->ReleaseStringUTFChars(output_path, output);
    return status;
}

}  // namespace

extern "C" JNIEXPORT const char* so_signer_native_version() {
    return kNativeVersion;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_signer_so_NativeMediaProcessor_nativeVersion(
        JNIEnv* environment,
        jclass type) {
    return nativeVersion(environment, type);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_signer_so_NativeMediaProcessor_probeTelephony(
        JNIEnv* environment,
        jclass type,
        jobject context,
        jstring expected_imei) {
    return probeTelephony(
            environment, type, context, expected_imei);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_signer_so_NativeMediaProcessor_appendFileCrc32(
        JNIEnv* environment,
        jclass type,
        jstring output_path) {
    return appendFileCrc32(environment, type, output_path);
}

/**
 * Computes CRC-32C from the complete existing file inside the shared library,
 * then appends ASCII "HASH" and the four raw little-endian CRC bytes.
 */
extern "C" JNIEXPORT int so_signer_append_file_crc32(
        const char* output_path) {
    return so_signer_append_file_crc32_with_imei(
            output_path, nullptr);
}

extern "C" JNIEXPORT int so_signer_append_file_crc32_with_imei(
        const char* output_path,
        const char* imei) {
    if (output_path == nullptr) {
        return kInvalidArgument;
    }
    if (imei != nullptr && !IsValidImei(imei)) {
        return kInvalidArgument;
    }

    std::FILE* input = std::fopen(output_path, "rb");
    if (input == nullptr) {
        return kInputOpenFailed;
    }

    std::unique_ptr<unsigned char[]> buffer(
            new (std::nothrow) unsigned char[kReadBufferSize]);
    if (buffer == nullptr) {
        std::fclose(input);
        return kBufferAllocationFailed;
    }

    int status = kSuccess;
    std::uint32_t checksum = 0xffffffffU;
    while (true) {
        const std::size_t count = std::fread(
                buffer.get(), 1, kReadBufferSize, input);
        if (count > 0) {
            checksum = UpdateCrc32c(checksum, buffer.get(), count);
        }
        if (count < kReadBufferSize) {
            if (std::ferror(input) != 0) {
                status = kInputReadFailed;
            }
            break;
        }
    }
    checksum ^= 0xffffffffU;

    if (std::fclose(input) != 0 && status == kSuccess) {
        status = kInputCloseFailed;
    }
    if (status != kSuccess) {
        return status;
    }

    std::FILE* output = std::fopen(output_path, "ab");
    if (output == nullptr) {
        return kOutputOpenFailed;
    }

    if (imei != nullptr
            && std::fwrite(
                    kImeiSeparator.data(),
                    1,
                    kImeiSeparator.size(),
                    output) != kImeiSeparator.size()) {
        status = kImeiSeparatorWriteFailed;
    }
    if (status == kSuccess
            && imei != nullptr
            && std::fwrite(
                    imei,
                    1,
                    kImeiLength,
                    output) != kImeiLength) {
        status = kImeiWriteFailed;
    }

    if (status == kSuccess
            && std::fwrite(
            kHashSeparator.data(),
            1,
            kHashSeparator.size(),
            output) != kHashSeparator.size()) {
        status = kSeparatorWriteFailed;
    }

    const std::array<unsigned char, 4> checksum_bytes = {
        static_cast<unsigned char>(checksum & 0xffU),
        static_cast<unsigned char>((checksum >> 8U) & 0xffU),
        static_cast<unsigned char>((checksum >> 16U) & 0xffU),
        static_cast<unsigned char>((checksum >> 24U) & 0xffU)
    };
    if (status == kSuccess
            && std::fwrite(
                    checksum_bytes.data(),
                    1,
                    checksum_bytes.size(),
                    output) != checksum_bytes.size()) {
        status = kChecksumWriteFailed;
    }

    if (std::fclose(output) != 0 && status == kSuccess) {
        status = kOutputCloseFailed;
    }
    return status;
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* /* virtualMachine */, void* /* reserved */) {
    return JNI_VERSION_1_6;
}
