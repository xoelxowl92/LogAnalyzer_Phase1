package org.openjfx.hellofx;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX App
 */
public class App extends Application {

    private static Scene scene;

    // 컨트롤러 간 공유 데이터
    static List<String[]> history = new ArrayList<>(); // [날짜, 파일명]
    static String currentFile = "";
    static String currentDate = "";

    @Override
    public void start(Stage stage) throws IOException {
        scene = new Scene(loadFXML("primary"), 1100, 750);
        scene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("LogAnalyzer");
        stage.show();
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch();
    }
}
