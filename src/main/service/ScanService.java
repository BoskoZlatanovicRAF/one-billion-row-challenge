package main.service;

import main.config.AppConfig;
import main.data.StationData;
import main.utils.FileUtils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ScanService {
    private final AppConfig config;
    private final ExecutorService executorService;
    private final Set<String> filesInUse;
    private final Map<String, Future<?>> namedJobs = new ConcurrentHashMap<>();

    public ScanService(AppConfig config, ExecutorService executorService, Set<String> filesInUse) {
        this.config = config;
        this.executorService = executorService;
        this.filesInUse = filesInUse;
    }

    public void executeScan(double min, double max, char targetLetter, String outputFile, String jobName) {
        Future<?> job = executorService.submit(() -> {
            try {
                scanFilesForTemperature(min, max, targetLetter, outputFile);
                System.out.println("Job " + jobName + " completed");
            } catch (Exception e) {
                System.err.println("Error in job " + jobName + ": " + e.getMessage());
            }
        });

        namedJobs.put(jobName, job);
        System.out.println("Job " + jobName + " submitted");
    }

    public void checkJobStatus(String jobName) {
        Future<?> job = namedJobs.get(jobName);
        if (job == null) {
            System.out.println(jobName + " is unknown");
        } else if (job.isDone()) {
            System.out.println(jobName + " is completed");
        } else if (job.isCancelled()) {
            System.out.println(jobName + " is cancelled");
        } else {
            System.out.println(jobName + " is running");
        }
    }

    public void saveUnexecutedJobs() {
        try (PrintWriter writer = new PrintWriter(new FileWriter("load_config"))) {
            for (Map.Entry<String, Future<?>> entry : namedJobs.entrySet()) {
                if (!entry.getValue().isDone() && !entry.getValue().isCancelled()) {
                    writer.println(entry.getKey());
                }
            }
            System.out.println("Unexecuted jobs saved to load_config");
        } catch (IOException e) {
            System.err.println("Error saving unexecuted jobs: " + e.getMessage());
        }
    }

    public void loadSavedJobs() {
        File configFile = new File("load_config");
        if (!configFile.exists()) {
            System.out.println("No saved jobs found");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String jobName = line.trim();
                if (!jobName.isEmpty()) {
                    System.out.println("Loaded job: " + jobName);
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading saved jobs: " + e.getMessage());
        }
    }

    private void scanFilesForTemperature(double min, double max, char targetLetter, String outputFile) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(config.getDirectoryPath()),
                    path -> FileUtils.isValidMeteoFile(path))) {

                for (Path file : stream) {
                    String filePath = file.toString();

                    // Skip files in use
                    if (filesInUse.contains(filePath)) {
                        continue;
                    }

                    // Mark file as in use
                    if (!filesInUse.add(filePath)) {
                        continue;
                    }

                    try {
                        scanSingleFile(file, min, max, targetLetter, writer);
                    } finally {
                        filesInUse.remove(filePath);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error during scan operation: " + e.getMessage());
        }
    }

    private void scanSingleFile(Path file, double min, double max, char targetLetter, PrintWriter writer) throws IOException {
        long fileSize = Files.size(file);
        long chunkSize = config.getChunkSize();
        int numChunks = (int) Math.ceil((double) fileSize / chunkSize);

        List<Future<List<String>>> chunkResults = new ArrayList<>();

        for (int i = 0; i < numChunks; i++) {
            final long startPosition = i * chunkSize;
            final long endPosition = Math.min(startPosition + chunkSize, fileSize);

            Future<List<String>> result = executorService.submit(
                    () -> scanFileChunk(file, startPosition, endPosition, min, max, targetLetter));

            chunkResults.add(result);
        }

        for (Future<List<String>> future : chunkResults) {
            try {
                List<String> matches = future.get();
                for (String match : matches) {
                    writer.println(match);
                }
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("Error during chunk scan: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<String> scanFileChunk(Path file, long startPosition, long endPosition,
                                       double min, double max, char targetLetter) {
        List<String> matches = new ArrayList<>();
        boolean isCsv = file.toString().toLowerCase().endsWith(".csv");

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(startPosition);

            // Skip to next line boundary if not at the beginning
            if (startPosition > 0) {
                raf.readLine();
            }

            // Process lines in this chunk until end position
            String line;
            while (raf.getFilePointer() < endPosition && (line = raf.readLine()) != null) {
                // Skip header only if CSV and at beginning of file
                if (isCsv && startPosition == 0 && raf.getFilePointer() == line.length() + 1) {
                    continue;
                }

                // Parse the line
                int semicolonIndex = line.indexOf(';');
                if (semicolonIndex > 0 && semicolonIndex < line.length() - 1) {
                    String stationName = line.substring(0, semicolonIndex).trim();
                    if (!stationName.isEmpty()) {
                        try {
                            double temperature = Double.parseDouble(line.substring(semicolonIndex + 1).trim());
                            char firstLetter = Character.toLowerCase(stationName.charAt(0));

                            if (firstLetter == targetLetter && temperature >= min && temperature <= max) {
                                matches.add(line);
                            }
                        } catch (NumberFormatException e) {
                            // Skip invalid temperature readings
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error processing chunk of file " + file.getFileName());
        }

        return matches;
    }
}