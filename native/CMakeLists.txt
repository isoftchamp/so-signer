cmake_minimum_required(VERSION 3.22.1)

project(so_signer_native LANGUAGES CXX)

add_library(
    so-signer-native
    SHARED
    src/main/cpp/native_media_processor.cpp
)

target_compile_features(so-signer-native PRIVATE cxx_std_17)

target_compile_options(
    so-signer-native
    PRIVATE
    -Wall
    -Wextra
    -Werror
    # Required for unidbg ARM32: JNI callbacks do not preserve Android's
    # process stack-canary state across the host/emulator boundary.
    -fno-stack-protector
)
