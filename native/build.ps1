param(
    [switch] $Clean
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$ndkRoot = if ($env:ANDROID_NDK_HOME) {
    $env:ANDROID_NDK_HOME
} else {
    Join-Path $sdkRoot "ndk\29.0.14206865"
}
$cmake = Join-Path $sdkRoot "cmake\3.31.6\bin\cmake.exe"
$ninja = Join-Path $sdkRoot "cmake\3.31.6\bin\ninja.exe"
$toolchain = Join-Path $ndkRoot "build\cmake\android.toolchain.cmake"
$buildDirectory = Join-Path $PSScriptRoot "build\armeabi-v7a"
$resourceDirectory = Join-Path $projectRoot "src\main\resources\native"
$library = Join-Path $buildDirectory "libso-signer-native.so"

foreach ($requiredFile in @($cmake, $ninja, $toolchain)) {
    if (-not (Test-Path -PathType Leaf $requiredFile)) {
        throw "Required Android build tool does not exist: $requiredFile"
    }
}

if ($Clean -and (Test-Path $buildDirectory)) {
    Remove-Item -Recurse -Force $buildDirectory
}

& $cmake `
    -S $PSScriptRoot `
    -B $buildDirectory `
    -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
    "-DANDROID_ABI=armeabi-v7a" `
    "-DANDROID_PLATFORM=android-23" `
    "-DCMAKE_BUILD_TYPE=Release"
if ($LASTEXITCODE -ne 0) {
    throw "Native CMake configuration failed with exit code $LASTEXITCODE"
}

& $cmake --build $buildDirectory
if ($LASTEXITCODE -ne 0) {
    throw "Native build failed with exit code $LASTEXITCODE"
}

New-Item -ItemType Directory -Force $resourceDirectory | Out-Null
Copy-Item -Force $library $resourceDirectory

Write-Host "Packaged native library: $resourceDirectory\libso-signer-native.so"
