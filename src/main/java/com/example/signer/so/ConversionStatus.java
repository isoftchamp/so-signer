package com.example.signer.so;

public enum ConversionStatus {
    PENDING("Pending", "status-pending"),
    CONVERTING("Converting", "status-converting"),
    SUCCESS("Success", "status-success"),
    FAILED("Failed", "status-failed");

    private final String displayName;
    private final String styleClass;

    ConversionStatus(String displayName, String styleClass) {
        this.displayName = displayName;
        this.styleClass = styleClass;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStyleClass() {
        return styleClass;
    }
}
