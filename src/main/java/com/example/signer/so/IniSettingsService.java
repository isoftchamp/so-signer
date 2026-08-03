package com.example.signer.so;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public final class IniSettingsService {

    private static final String APPLICATION_DIRECTORY = "so-signer";
    private static final String SETTINGS_FILE = "settings.ini";

    private final Path settingsPath;

    public IniSettingsService() {
        this(resolveSettingsPath());
    }

    IniSettingsService(Path settingsPath) {
        this.settingsPath = settingsPath;
    }

    public AppSettings load() {
        AppSettings settings = new AppSettings();
        if (!Files.isRegularFile(settingsPath)) {
            return settings;
        }

        try {
            Map<String, String> values = readValues();
            settings.setVideoEnabled(readBoolean(values, "media.video", true));
            settings.setAudioEnabled(readBoolean(values, "media.audio", false));

            String outputDirectory = values.get("output.directory");
            if (outputDirectory != null && !outputDirectory.trim().isEmpty()) {
                settings.setOutputDirectory(Paths.get(outputDirectory.trim()));
            }

            String inputDirectory = values.get("input.directory");
            if (inputDirectory != null && !inputDirectory.trim().isEmpty()) {
                settings.setLastOpenedDirectory(Paths.get(inputDirectory.trim()));
            }
        } catch (IOException | InvalidPathException exception) {
            System.err.println("Could not read settings from " + settingsPath + ": "
                    + exception.getMessage());
        }
        return settings;
    }

    public void save(AppSettings settings) throws IOException {
        Files.createDirectories(settingsPath.getParent());
        Path temporaryPath = settingsPath.resolveSibling(SETTINGS_FILE + ".tmp");

        try (BufferedWriter writer = Files.newBufferedWriter(
                temporaryPath, StandardCharsets.UTF_8)) {
            writer.write("[media]");
            writer.newLine();
            writer.write("video=" + settings.isVideoEnabled());
            writer.newLine();
            writer.write("audio=" + settings.isAudioEnabled());
            writer.newLine();
            writer.newLine();
            writer.write("[output]");
            writer.newLine();
            writer.write("directory=" + settings.getOutputDirectory().toAbsolutePath());
            writer.newLine();
            writer.newLine();
            writer.write("[input]");
            writer.newLine();
            writer.write("directory=");
            if (settings.getLastOpenedDirectory() != null) {
                writer.write(settings.getLastOpenedDirectory().toAbsolutePath().toString());
            }
            writer.newLine();
        }

        try {
            Files.move(temporaryPath, settingsPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryPath, settingsPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path getSettingsPath() {
        return settingsPath;
    }

    private Map<String, String> readValues() throws IOException {
        Map<String, String> values = new HashMap<>();
        String section = "";

        try (BufferedReader reader = Files.newBufferedReader(
                settingsPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1)
                            .trim().toLowerCase();
                    continue;
                }

                int separator = trimmed.indexOf('=');
                if (separator > 0) {
                    String key = trimmed.substring(0, separator).trim().toLowerCase();
                    String value = trimmed.substring(separator + 1).trim();
                    values.put(section + "." + key, value);
                }
            }
        }
        return values;
    }

    private boolean readBoolean(Map<String, String> values, String key,
                                boolean defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static Path resolveSettingsPath() {
        String appData = System.getenv("APPDATA");
        Path baseDirectory;
        if (appData == null || appData.trim().isEmpty()) {
            baseDirectory = Paths.get(System.getProperty("user.home"), "AppData", "Roaming");
        } else {
            baseDirectory = Paths.get(appData);
        }
        return baseDirectory.resolve(APPLICATION_DIRECTORY).resolve(SETTINGS_FILE);
    }
}
