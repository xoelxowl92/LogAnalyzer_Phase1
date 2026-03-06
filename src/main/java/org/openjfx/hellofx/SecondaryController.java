package org.openjfx.hellofx;

import java.io.IOException;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;

public class SecondaryController {

    @FXML
    private TextArea resultsTextArea;

    @FXML
    private void initialize() {
        if (resultsTextArea != null) {
            resultsTextArea.setText("결과가 여기에 표시됩니다.");
        }
    }

    @FXML
    private void switchToPrimary() throws IOException {
        App.setRoot("primary");
    }
}