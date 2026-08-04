package com.example.signer.so;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Conversion boundary backed by an emulated Android runtime.
 *
 * <p>The emulator and the .so are initialized lazily on the conversion worker,
 * not on the JavaFX application thread. Calls are synchronized because one
 * unidbg VM should be treated as single-threaded unless the native wrapper is
 * specifically designed for concurrency.</p>
 */
public final class AndroidSoMediaConverter implements MediaConverter {

    private AndroidSoRuntime runtime;

    @Override
    public synchronized void convert(Path input, Path output) throws Exception {
        AndroidSoRuntime android = runtime();
        copyFile(input, output);

        /*
         * The application currently uses the dynamically registered JNI
         * method. To call the exported C function directly through unidbg,
         * replace the next line with:
         *
         * int status = android.appendFileCrc32WithSystemLibrary(output);
         *
         * Do not call both methods for the same output file: each invocation
         * appends its own "HASH" separator and CRC bytes.
         */
        int status = android.appendFileCrc32WithDynamicJni(output);
        if (status != 0) {
            Files.deleteIfExists(output);
            throw new IOException("Native checksum append failed: "
                    + statusDescription(status) + " (status " + status + ")");
        }
        long checksum = readAppendedCrc32(output);
        System.out.println("[Custom SO] CRC-32C: "
                + String.format("%08x", checksum));
        System.out.println("[Custom SO] Appended: ASCII HASH + 4 raw bytes");
        System.out.println("[Custom SO] Output: " + output.toAbsolutePath());
    }

    private AndroidSoRuntime runtime() throws IOException {
        if (runtime == null) {
            runtime = new AndroidSoRuntime();
            System.out.println("[Custom SO] Dynamic JNI: "
                    + runtime.getDynamicJniVersion());
            System.out.println("[Custom SO] System library: "
                    + runtime.getSystemLibraryVersion());
        }
        return runtime;
    }

    private void copyFile(Path input, Path output) throws IOException {
        Path absoluteInput = input.toAbsolutePath().normalize();
        Path absoluteOutput = output.toAbsolutePath().normalize();
        if (absoluteInput.equals(absoluteOutput)) {
            throw new IOException(
                    "Input and output must be different files");
        }

        Path outputParent = absoluteOutput.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }
        Files.copy(absoluteInput, absoluteOutput,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private long readAppendedCrc32(Path output) throws IOException {
        ByteBuffer checksumBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel channel = FileChannel.open(
                output, StandardOpenOption.READ)) {
            if (channel.size() < 8) {
                throw new IOException(
                        "Native output is too short to contain a checksum");
            }
            channel.position(channel.size() - checksumBytes.capacity());
            while (checksumBytes.hasRemaining()) {
                if (channel.read(checksumBytes) < 0) {
                    throw new IOException(
                            "Could not read the appended native checksum");
                }
            }
        }
        checksumBytes.flip();
        return Integer.toUnsignedLong(checksumBytes.getInt());
    }

    private String statusDescription(int status) {
        switch (status) {
            case 1:
                return "invalid path argument";
            case 2:
                return "file could not be opened for CRC calculation";
            case 3:
                return "native CRC buffer could not be allocated";
            case 4:
                return "file could not be read for CRC calculation";
            case 5:
                return "CRC input file could not be closed";
            case 6:
                return "file could not be opened for appending";
            case 7:
                return "HASH separator could not be written";
            case 8:
                return "CRC bytes could not be written";
            case 9:
                return "output file could not be closed";
            default:
                return "unknown native error";
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
