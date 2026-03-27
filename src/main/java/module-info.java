module hellofx {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.json;
    requires transitive javafx.graphics;
    requires com.fasterxml.jackson.databind;

    opens org.openjfx.hellofx to javafx.fxml, com.fasterxml.jackson.databind;
    exports org.openjfx.hellofx;
}