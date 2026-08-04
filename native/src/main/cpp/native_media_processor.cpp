#include <jni.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <new>

extern "C" JNIEXPORT int so_signer_append_file_crc32(
        const char* output_path);

namespace {

constexpr char kNativeVersion[] = "so-signer-native/3";
constexpr std::size_t kReadBufferSize = 1024 * 1024;
constexpr std::uint32_t kCrc32cPolynomial = 0x82f63b78U;
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
    kOutputCloseFailed = 9
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

    const int status = so_signer_append_file_crc32(output);
    environment->ReleaseStringUTFChars(output_path, output);
    return status;
}

/*
 * These C++ function names intentionally match the native method declarations
 * in NativeMediaProcessor.java. RegisterNatives connects each Java
 * name/signature pair to its function pointer when JNI_OnLoad runs.
 */
const JNINativeMethod kNativeMethods[] = {
    {
        "nativeVersion",
        "()Ljava/lang/String;",
        reinterpret_cast<void*>(nativeVersion)
    },
    {
        "appendFileCrc32",
        "(Ljava/lang/String;)I",
        reinterpret_cast<void*>(appendFileCrc32)
    }
};

}  // namespace

extern "C" JNIEXPORT const char* so_signer_native_version() {
    return kNativeVersion;
}

/**
 * Computes CRC-32C from the complete existing file inside the shared library,
 * then appends ASCII "HASH" and the four raw little-endian CRC bytes.
 */
extern "C" JNIEXPORT int so_signer_append_file_crc32(
        const char* output_path) {
    if (output_path == nullptr) {
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

    if (std::fwrite(
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
JNI_OnLoad(JavaVM* virtualMachine, void* /* reserved */) {
    JNIEnv* environment = nullptr;
    if (virtualMachine->GetEnv(
            reinterpret_cast<void**>(&environment),
            JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass processorClass = environment->FindClass(
            "com/example/signer/so/NativeMediaProcessor");
    if (processorClass == nullptr) {
        return JNI_ERR;
    }

    constexpr jint methodCount = static_cast<jint>(
            sizeof(kNativeMethods) / sizeof(kNativeMethods[0]));
    if (environment->RegisterNatives(
            processorClass, kNativeMethods, methodCount) != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
