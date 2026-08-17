package com.example.signer.so;

import java.nio.file.Path;

public final class AppSettings {

    public static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("D:\\output");

    private boolean videoEnabled;
    private boolean audioEnabled;
    private Path outputDirectory;
    private Path lastOpenedDirectory;
    private String imei;

    public AppSettings() {
        this(true, false, DEFAULT_OUTPUT_DIRECTORY, null, "");
    }

    public AppSettings(boolean videoEnabled, boolean audioEnabled, Path outputDirectory,
                       Path lastOpenedDirectory, String imei) {
        this.videoEnabled = videoEnabled;
        this.audioEnabled = audioEnabled;
        this.outputDirectory = outputDirectory;
        this.lastOpenedDirectory = lastOpenedDirectory;
        this.imei = imei;
    }

    public AppSettings copy() {
        return new AppSettings(videoEnabled, audioEnabled, outputDirectory,
                lastOpenedDirectory, imei);
    }

    public boolean isVideoEnabled() {
        return videoEnabled;
    }

    public void setVideoEnabled(boolean videoEnabled) {
        this.videoEnabled = videoEnabled;
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public void setAudioEnabled(boolean audioEnabled) {
        this.audioEnabled = audioEnabled;
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public Path getLastOpenedDirectory() {
        return lastOpenedDirectory;
    }

    public void setLastOpenedDirectory(Path lastOpenedDirectory) {
        this.lastOpenedDirectory = lastOpenedDirectory;
    }

    public String getImei() {
        return imei;
    }

    public void setImei(String imei) {
        this.imei = imei;
    }
}
