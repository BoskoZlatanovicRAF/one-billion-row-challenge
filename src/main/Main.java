package main;

import main.processors.MeteorologicalDataProcessor;

public class Main {
    public static void main(String[] args) {
        String directoryPath;

        if (args.length < 1) {
            // Use a default directory path if none is provided
            directoryPath = "test_data";
            System.out.println("No directory specified, using default: " + directoryPath);
        } else {
            directoryPath = args[0];
        }

        MeteorologicalDataProcessor processor = new MeteorologicalDataProcessor(directoryPath);
        processor.start(false);
    }
}