module hellofx {
    requires javafx.controls;
    requires javafx.fxml;
    requires transitive javafx.graphics;
    requires com.fasterxml.jackson.databind;
    requires jdk.crypto.ec;

    opens org.openjfx.hellofx to javafx.fxml, com.fasterxml.jackson.databind;
    exports org.openjfx.hellofx;
}