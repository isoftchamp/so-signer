package com.example.signer.so;

import java.nio.file.Path;

@FunctionalInterface
public interface MediaConverter {

    void convert(Path input, Path output) throws Exception;
}
