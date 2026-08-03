package com.example.signer.so;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * JavaFX App
 */
public class App extends Application {

    private PrimaryController primaryController;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(App.class.getResource("primary.fxml"));
        Parent root = loader.load();
        primaryController = loader.getController();

        Scene scene = new Scene(root, 900, 600);
        stage.setTitle("Media Converter");
        stage.setMinWidth(720);
        stage.setMinHeight(480);
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        if (primaryController != null) {
            primaryController.shutdown();
        }
    }

    public static void main(String[] args) {
        launch();
    }

}