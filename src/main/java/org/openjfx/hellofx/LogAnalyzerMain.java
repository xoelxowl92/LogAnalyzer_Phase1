package org.openjfx.hellofx;

import org.json.JSONArray;

public class LogAnalyzerMain {

    public static void main(String[] args) {

        //String filePath = "D:\\git\\large_sample.log";
        String filePath = "D:\\git\\logback.log";
        LogPreprocessor processor = new LogPreprocessor();

        JSONArray result = processor.processFile(filePath);

        System.out.println(result.toString(2));
    }
}