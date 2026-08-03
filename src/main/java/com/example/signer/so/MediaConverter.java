package com.example.signer.so;

import java.nio.file.Path;

@FunctionalInterface
public interface MediaConverter extends AutoCloseable {

    void convert(Path input, Path output) throws Exception;

    @Override
    default void close() throws Exception {
        // Most converter implementations do not own closeable resources.
    }
}
