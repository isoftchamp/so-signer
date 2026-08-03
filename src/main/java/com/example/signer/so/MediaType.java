package com.example.signer.so;

public enum MediaType {
    VIDEO("Video"),
    AUDIO("Audio");

    private final String displayName;

    MediaType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
