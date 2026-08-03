module com.example.signer.so {
    requires javafx.controls;
    requires javafx.fxml;

    opens com.example.signer.so to javafx.fxml;
    exports com.example.signer.so;
}
