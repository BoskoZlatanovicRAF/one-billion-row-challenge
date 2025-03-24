package main.io;

import main.jobs.impl.ProcessFileJob;
import main.service.MapService;
import main.utils.FileUtils;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class DirectoryMonitor implements Runnable {
    private final Path directoryPath;
    private final AtomicBoolean isRunning;
    private final FileProcessor fileProcessor;
    private final MapService mapService;
    private final Map<String, Long> fileLastModifiedMap = new ConcurrentHashMap<>();

    public DirectoryMonitor(Path directoryPath, AtomicBoolean isRunning,
                            FileProcessor fileProcessor, MapService mapService) {
        this.directoryPath = directoryPath;
        this.isRunning = isRunning;
        this.fileProcessor = fileProcessor;
        this.mapService = mapService;
    }

    @Override
    public void run() {
        System.out.println("Monitoring directory: " + directoryPath);

        try {
            // Process all files initially
            checkAndProcessDirectoryFiles();

            while (isRunning.get()) {
                try {
                    boolean changes = checkForFileChanges();

                    if (changes) {
                        checkAndProcessDirectoryFiles();
                    }

                    Thread.sleep(5000); // Check every 5 seconds
                } catch (IOException e) {
                    System.err.println("Error monitoring directory: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Directory monitoring thread error: " + e.getMessage());
        }

        System.out.println("Directory monitoring thread terminated.");
    }

    private boolean checkForFileChanges() throws IOException {
        boolean changes = false;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath,
                path -> FileUtils.isValidMeteoFile(path))) {

            Set<String> currentFiles = new HashSet<>();

            for (Path file : stream) {
                String fileName = file.toString();
                currentFiles.add(fileName);

                long lastModified = Files.getLastModifiedTime(file).toMillis();

                if (!fileLastModifiedMap.containsKey(fileName) ||
                        fileLastModifiedMap.get(fileName) != lastModified) {

                    System.out.println("Change detected in file: " + file.getFileName());
                    fileLastModifiedMap.put(fileName, lastModified);
                    changes = true;
                }
            }

            // Check for deleted files
            Set<String> deletedFiles = new HashSet<>(fileLastModifiedMap.keySet());
            deletedFiles.removeAll(currentFiles);

            for (String deletedFile : deletedFiles) {
                System.out.println("File deleted: " + Paths.get(deletedFile).getFileName());
                fileLastModifiedMap.remove(deletedFile);
                changes = true;
            }
        }

        return changes;
    }

    private void checkAndProcessDirectoryFiles() {
        try {
            // Clear the in-memory map before processing all files
            mapService.clearMap();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath,
                    path -> FileUtils.isValidMeteoFile(path))) {

                for (Path file : stream) {
                    fileProcessor.processFile(file);
                }
            }
        } catch (IOException e) {
            System.err.println("Error processing directory files: " + e.getMessage());
        }
    }
}