package com.example.signer.so;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;

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

        try (BufferedReader reader = Files.newBufferedReader(
                settingsPath, StandardCharsets.UTF_8)) {
            INIConfiguration configuration = new INIConfiguration();
            configuration.read(reader);

            settings.setVideoEnabled(
                    configuration.getBoolean("media.video", true));
            settings.setAudioEnabled(
                    configuration.getBoolean("media.audio", false));

            String outputDirectory =
                    configuration.getString("output.directory");
            if (outputDirectory != null && !outputDirectory.isBlank()) {
                settings.setOutputDirectory(Path.of(outputDirectory.strip()));
            }

            String inputDirectory =
                    configuration.getString("input.directory");
            if (inputDirectory != null && !inputDirectory.isBlank()) {
                settings.setLastOpenedDirectory(Path.of(inputDirectory.strip()));
            }
        } catch (IOException | ConfigurationException
                 | InvalidPathException exception) {
            System.err.println("Could not read settings from " + settingsPath + ": "
                    + exception.getMessage());
        }
        return settings;
    }

    public void save(AppSettings settings) throws IOException {
        Files.createDirectories(settingsPath.getParent());
        Path temporaryPath = settingsPath.resolveSibling(SETTINGS_FILE + ".tmp");

        INIConfiguration configuration = new INIConfiguration();
        configuration.setProperty("media.video", settings.isVideoEnabled());
        configuration.setProperty("media.audio", settings.isAudioEnabled());
        configuration.setProperty("output.directory",
                settings.getOutputDirectory().toAbsolutePath().toString());
        configuration.setProperty("input.directory",
                settings.getLastOpenedDirectory() == null
                        ? ""
                        : settings.getLastOpenedDirectory()
                                .toAbsolutePath().toString());

        try (BufferedWriter writer = Files.newBufferedWriter(
                temporaryPath, StandardCharsets.UTF_8)) {
            configuration.write(writer);
        } catch (ConfigurationException exception) {
            throw new IOException("Could not write INI settings", exception);
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

    private static Path resolveSettingsPath() {
        String appData = System.getenv("APPDATA");
        Path baseDirectory;
        if (appData == null || appData.isBlank()) {
            baseDirectory = Path.of(
                    System.getProperty("user.home"), "AppData", "Roaming");
        } else {
            baseDirectory = Path.of(appData.strip());
        }
        return baseDirectory.resolve(APPLICATION_DIRECTORY).resolve(SETTINGS_FILE);
    }
}
