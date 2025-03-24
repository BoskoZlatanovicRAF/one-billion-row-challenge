package main.utils;

import java.nio.file.Path;

public class FileUtils {

    public static boolean isValidMeteoFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".txt") || fileName.endsWith(".csv");
    }
}