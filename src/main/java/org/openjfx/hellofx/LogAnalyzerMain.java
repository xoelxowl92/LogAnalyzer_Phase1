package org.openjfx.hellofx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class LogAnalyzerMain {

    public static void main(String[] args) throws Exception {

        //String filePath = "D:\\git\\large_sample.log";
        String filePath = "D:\\git\\logback.log";
        LogPreprocessor processor = new LogPreprocessor();

        ArrayNode result = processor.processFile(filePath);

        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}