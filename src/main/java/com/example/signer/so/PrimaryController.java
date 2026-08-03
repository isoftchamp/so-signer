package com.example.signer.so;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

public final class PrimaryController {

    private final ObservableList<MediaFileItem> files =
            FXCollections.observableArrayList();
    private final BooleanProperty converting = new SimpleBooleanProperty(false);
    private final MediaScanner mediaScanner = new MediaScanner();
    private final IniSettingsService settingsService = new IniSettingsService();
    private final MediaConverter mediaConverter = new DefaultMediaConverter();

    private AppSettings settings;
    private Path selectedFolder;

    @FXML
    private TableView<MediaFileItem> fileTable;

    @FXML
    private TableColumn<MediaFileItem, String> nameColumn;

    @FXML
    private TableColumn<MediaFileItem, String> typeColumn;

    @FXML
    private TableColumn<MediaFileItem, String> pathColumn;

    @FXML
    private TableColumn<MediaFileItem, ConversionStatus> statusColumn;

    @FXML
    private Button openFolderButton;

    @FXML
    private Button convertButton;

    @FXML
    private Button settingsButton;

    @FXML
    private Button removeButton;

    @FXML
    private Button clearButton;

    @FXML
    private Label folderLabel;

    @FXML
    private Label summaryLabel;

    @FXML
    private void initialize() {
        settings = settingsService.load();
        configureTable();
        configureButtons();
        fileTable.setItems(files);
        updateSummary();

        Path lastOpenedDirectory = settings.getLastOpenedDirectory();
        if (lastOpenedDirectory != null && Files.isDirectory(lastOpenedDirectory)) {
            selectedFolder = lastOpenedDirectory;
            refreshFiles();
        }
    }

    @FXML
    private void openFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select media folder");
        Path initialDirectory = selectedFolder != null
                ? selectedFolder : settings.getLastOpenedDirectory();
        if (initialDirectory != null && Files.isDirectory(initialDirectory)) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }

        File selected = chooser.showDialog(window());
        if (selected != null) {
            selectedFolder = selected.toPath();
            settings.setLastOpenedDirectory(selectedFolder);
            try {
                settingsService.save(settings);
            } catch (IOException exception) {
                showError("Could not save the last opened folder",
                        exception.getMessage());
            }
            refreshFiles();
        }
    }

    @FXML
    private void clearFiles() {
        selectedFolder = null;
        files.clear();
        folderLabel.setText("No folder selected");
        updateSummary();
    }

    @FXML
    private void removeSelectedFiles() {
        List<MediaFileItem> selectedItems = new ArrayList<>(
                fileTable.getSelectionModel().getSelectedItems());
        files.removeAll(selectedItems);
        fileTable.getSelectionModel().clearSelection();
        updateSummary();
    }

    @FXML
    private void openSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(App.class.getResource("settings.fxml"));
            Parent content = loader.load();
            SettingsController controller = loader.getController();
            controller.setSettings(settings.copy());

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(window());
            dialog.setTitle("Settings");
            dialog.setHeaderText("Media and output settings");
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().getButtonTypes().addAll(
                    ButtonType.APPLY, ButtonType.CANCEL);

            Optional<ButtonType> result = dialog.showAndWait();
            if (result.orElse(ButtonType.CANCEL) == ButtonType.APPLY) {
                AppSettings updatedSettings = controller.getSettings();
                validateSettings(updatedSettings);
                settingsService.save(updatedSettings);
                settings = updatedSettings;
                if (selectedFolder != null) {
                    refreshFiles();
                }
            }
        } catch (Exception exception) {
            showError("Could not save settings", exception.getMessage());
        }
    }

    @FXML
    private void convertFiles() {
        if (files.isEmpty() || converting.get()) {
            return;
        }

        List<MediaFileItem> workItems = new ArrayList<>(files);
        Path sourceDirectory = selectedFolder.toAbsolutePath().normalize();
        Path configuredOutputDirectory =
                settings.getOutputDirectory().toAbsolutePath().normalize();
        Task<Void> conversionTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                Files.createDirectories(configuredOutputDirectory);

                for (MediaFileItem item : workItems) {
                    if (isCancelled()) {
                        break;
                    }
                    updateItem(item, ConversionStatus.CONVERTING, "");
                    try {
                        Path relativePath = sourceDirectory.relativize(
                                item.getPath().toAbsolutePath().normalize());
                        Path outputParent = configuredOutputDirectory
                                .resolve(relativePath).getParent();
                        Files.createDirectories(outputParent);
                        Path output = nextAvailableOutput(
                                outputParent, relativePath.getFileName().toString());
                        mediaConverter.convert(item.getPath(), output);
                        updateItem(item, ConversionStatus.SUCCESS,
                                "Output: " + output);
                    } catch (Exception exception) {
                        updateItem(item, ConversionStatus.FAILED,
                                exception.getMessage());
                    }
                }
                return null;
            }
        };

        converting.set(true);
        conversionTask.setOnSucceeded(event -> {
            converting.set(false);
            updateSummary();
        });
        conversionTask.setOnFailed(event -> {
            converting.set(false);
            Throwable error = conversionTask.getException();
            showError("Conversion could not start",
                    error == null ? "Unknown error" : error.getMessage());
        });

        Thread worker = new Thread(conversionTask, "media-converter");
        worker.setDaemon(true);
        worker.start();
    }

    private void configureTable() {
        fileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        nameColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().getFileName()));
        typeColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().getMediaType().getDisplayName()));
        pathColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().getPath().toString()));
        statusColumn.setCellValueFactory(cell -> cell.getValue().statusProperty());
        statusColumn.setCellFactory(column -> new StatusTableCell());
    }

    private void configureButtons() {
        openFolderButton.setGraphic(ButtonIcons.folder());
        convertButton.setGraphic(ButtonIcons.convert());
        settingsButton.setGraphic(ButtonIcons.settings());
        removeButton.setGraphic(ButtonIcons.remove());
        clearButton.setGraphic(ButtonIcons.clear());

        convertButton.disableProperty().bind(
                Bindings.isEmpty(files).or(converting));
        openFolderButton.disableProperty().bind(converting);
        settingsButton.disableProperty().bind(converting);
        removeButton.disableProperty().bind(
                Bindings.isEmpty(fileTable.getSelectionModel().getSelectedItems())
                        .or(converting));
        clearButton.disableProperty().bind(
                Bindings.isEmpty(files).or(converting));
    }

    private void refreshFiles() {
        try {
            files.setAll(mediaScanner.scan(selectedFolder, settings));
            folderLabel.setText(selectedFolder.toAbsolutePath().toString());
            updateSummary();
        } catch (IOException exception) {
            files.clear();
            updateSummary();
            showError("Could not open folder", exception.getMessage());
        }
    }

    private void updateItem(MediaFileItem item, ConversionStatus status,
                            String message) {
        Platform.runLater(() -> {
            item.setMessage(message);
            item.setStatus(status);
            updateSummary();
        });
    }

    private void updateSummary() {
        long successCount = files.stream()
                .filter(item -> item.getStatus() == ConversionStatus.SUCCESS)
                .count();
        long failedCount = files.stream()
                .filter(item -> item.getStatus() == ConversionStatus.FAILED)
                .count();

        StringBuilder summary = new StringBuilder(files.size() + " files");
        if (successCount > 0) {
            summary.append("  •  ").append(successCount).append(" succeeded");
        }
        if (failedCount > 0) {
            summary.append("  •  ").append(failedCount).append(" failed");
        }
        summaryLabel.setText(summary.toString());
    }

    private Path nextAvailableOutput(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0
                ? fileName.substring(0, extensionIndex) : fileName;
        String extension = extensionIndex > 0
                ? fileName.substring(extensionIndex) : "";
        int suffix = 1;
        do {
            candidate = directory.resolve(
                    baseName + " (" + suffix++ + ")" + extension);
        } while (Files.exists(candidate));
        return candidate;
    }

    private void validateSettings(AppSettings candidate) {
        if (candidate.getOutputDirectory().toString().trim().isEmpty()) {
            throw new IllegalArgumentException("Output folder cannot be empty.");
        }
    }

    private Window window() {
        return fileTable.getScene().getWindow();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(window());
        alert.setTitle("Media Converter");
        alert.setHeaderText(title);
        alert.setContentText(message == null || message.isEmpty()
                ? "An unknown error occurred." : message);
        alert.showAndWait();
    }

    private static final class StatusTableCell
            extends TableCell<MediaFileItem, ConversionStatus> {

        private final Label badge = new Label();

        private StatusTableCell() {
            badge.getStyleClass().add("status-badge");
        }

        @Override
        protected void updateItem(ConversionStatus status, boolean empty) {
            super.updateItem(status, empty);
            badge.getStyleClass().removeAll(
                    "status-pending", "status-converting",
                    "status-success", "status-failed");

            if (empty || status == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }

            badge.setText(status.getDisplayName());
            badge.getStyleClass().add(status.getStyleClass());
            setGraphic(badge);

            MediaFileItem rowItem = getTableRow() == null
                    ? null : getTableRow().getItem();
            String message = rowItem == null ? "" : rowItem.getMessage();
            setTooltip(message.isEmpty() ? null : new Tooltip(message));
        }
    }
}
