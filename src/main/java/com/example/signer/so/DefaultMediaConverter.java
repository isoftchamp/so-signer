package com.example.signer.so;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Temporary conversion implementation. Replace the copy operation with the
 * application's media transformation logic.
 */
public final class DefaultMediaConverter implements MediaConverter {

    @Override
    public void convert(Path input, Path output) throws IOException {
        Files.copy(input, output);
    }
}
