package com.example.signer.so;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

public final class SettingsController {

    private Path lastOpenedDirectory;
    private String imei;

    @FXML
    private CheckBox videoCheckBox;

    @FXML
    private CheckBox audioCheckBox;

    @FXML
    private TextField outputDirectoryField;

    @FXML
    private Button browseButton;

    @FXML
    private void initialize() {
        browseButton.setGraphic(ButtonIcons.folder());
    }

    public void setSettings(AppSettings settings) {
        videoCheckBox.setSelected(settings.isVideoEnabled());
        audioCheckBox.setSelected(settings.isAudioEnabled());
        outputDirectoryField.setText(settings.getOutputDirectory().toString());
        lastOpenedDirectory = settings.getLastOpenedDirectory();
        imei = settings.getImei();
    }

    public AppSettings getSettings() {
        String outputValue = outputDirectoryField.getText().strip();
        Path outputDirectory = Path.of(outputValue);
        return new AppSettings(videoCheckBox.isSelected(), audioCheckBox.isSelected(),
                outputDirectory, lastOpenedDirectory, imei);
    }

    @FXML
    private void browseOutputDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select output folder");

        String outputValue = outputDirectoryField.getText();
        if (!outputValue.isBlank()) {
            try {
                Path currentPath = Path.of(outputValue.strip());
                if (Files.isDirectory(currentPath)) {
                    chooser.setInitialDirectory(currentPath.toFile());
                }
            } catch (InvalidPathException ignored) {
                // The user can replace an invalid value through the chooser.
            }
        }

        Window owner = browseButton.getScene().getWindow();
        File selectedDirectory = chooser.showDialog(owner);
        if (selectedDirectory != null) {
            outputDirectoryField.setText(selectedDirectory.getAbsolutePath());
        }
    }
}
