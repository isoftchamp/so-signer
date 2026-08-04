package com.example.signer.so;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class MediaScanner {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4",
            "mpeg", "mpg", "ogv", "ts", "vob", "webm", "wmv");

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "aac", "ac3", "aif", "aiff", "alac", "amr", "flac", "m4a",
            "mp3", "ogg", "opus", "wav", "wma");

    public List<MediaFileItem> scan(Path directory, AppSettings settings)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Folder does not exist: " + directory);
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> toMediaItem(path, settings))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(
                            MediaFileItem::getFileName, String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
        }
    }

    private Optional<MediaFileItem> toMediaItem(Path path, AppSettings settings) {
        String extension = extensionOf(path);
        if (settings.isVideoEnabled() && VIDEO_EXTENSIONS.contains(extension)) {
            return Optional.of(new MediaFileItem(path, MediaType.VIDEO));
        }
        if (settings.isAudioEnabled() && AUDIO_EXTENSIONS.contains(extension)) {
            return Optional.of(new MediaFileItem(path, MediaType.AUDIO));
        }
        return Optional.empty();
    }

    private String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int separator = name.lastIndexOf('.');
        if (separator < 0 || separator == name.length() - 1) {
            return "";
        }
        return name.substring(separator + 1).toLowerCase(Locale.ROOT);
    }
}
