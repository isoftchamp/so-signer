package com.example.signer.so;

import java.nio.file.Path;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class MediaFileItem {

    private final Path path;
    private final MediaType mediaType;
    private final ObjectProperty<ConversionStatus> status =
            new SimpleObjectProperty<>(ConversionStatus.PENDING);
    private final StringProperty message = new SimpleStringProperty("");

    public MediaFileItem(Path path, MediaType mediaType) {
        this.path = path;
        this.mediaType = mediaType;
    }

    public Path getPath() {
        return path;
    }

    public String getFileName() {
        return path.getFileName().toString();
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public ConversionStatus getStatus() {
        return status.get();
    }

    public void setStatus(ConversionStatus status) {
        this.status.set(status);
    }

    public ObjectProperty<ConversionStatus> statusProperty() {
        return status;
    }

    public String getMessage() {
        return message.get();
    }

    public void setMessage(String message) {
        this.message.set(message == null ? "" : message);
    }

    public StringProperty messageProperty() {
        return message;
    }
}
