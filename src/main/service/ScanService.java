package main.service;

import main.config.AppConfig;
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
                processAllFiles(min, max, targetLetter, outputFile);
                System.out.println("Job " + jobName + " completed");
            } catch (Exception e) {
                System.err.println("Error in job " + jobName + ": " + e.getMessage());
            }
        });
        namedJobs.put(jobName, job);
        System.out.println("Job " + jobName + " submitted");
    }

    private void processAllFiles(double min, double max, char targetLetter, String outputFile) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            List<Future<List<String>>> futures = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(config.getDirectoryPath()),
                    path -> FileUtils.isValidMeteoFile(path))) {
                for (Path file : stream) {
                    futures.add(executorService.submit(() -> processSingleFile(file, min, max, targetLetter)));
                }
            }

            // Collect results
            for (Future<List<String>> future : futures) {
                List<String> matches = future.get();
                for (String line : matches) {
                    writer.println(line);
                }
            }
        } catch (IOException | InterruptedException | ExecutionException e) {
            System.err.println("Scan failed: " + e.getMessage());
        }
    }

    private List<String> processSingleFile(Path file, double min, double max, char targetLetter) {
        List<String> matches = new ArrayList<>();
        boolean isCsv = file.toString().endsWith(".csv");

        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()), 8 * 1024 * 1024)) {
            String line;
            boolean isHeader = isCsv;

            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue; // Skip CSV header
                }

                int semicolonPos = line.indexOf(';');
                if (semicolonPos < 1) continue;

                String station = line.substring(0, semicolonPos).trim();
                if (station.isEmpty()) continue;

                char firstChar = Character.toLowerCase(station.charAt(0));
                if (firstChar != targetLetter) continue;

                try {
                    double temp = Double.parseDouble(line.substring(semicolonPos + 1).trim());
                    if (temp >= min && temp <= max) {
                        matches.add(line);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("Error reading " + file.getFileName());
        }

        return matches;
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
}
