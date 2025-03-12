package main.processors;

import main.data.StationData;
import main.jobs.Job;
import main.jobs.impl.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MeteorologicalDataProcessor {
    private final Path directoryPath;
    private final Map<String, Long> fileLastModifiedMap = new ConcurrentHashMap<>();
    private final Map<Character, StationData> inMemoryMap = new ConcurrentHashMap<>();
    private final BlockingQueue<Job> jobQueue = new LinkedBlockingQueue<>();
    private final Map<String, Future<?>> namedJobs = new ConcurrentHashMap<>();
    private final ExecutorService fileProcessorService = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduledService = Executors.newSingleThreadScheduledExecutor();
    private final Set<String> filesInUse = Collections.synchronizedSet(new HashSet<>());
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final Object exportLock = new Object();
    private final ExecutorCompletionService<Map<Character, StationData>> completionService =
            new ExecutorCompletionService<>(fileProcessorService);

    // Threads
    private Thread directoryMonitorThread;
    private Thread jobProcessorThread;
    private Thread commandThread;

    public MeteorologicalDataProcessor(String directory) {
        this.directoryPath = Paths.get(directory);
        if (!Files.isDirectory(directoryPath)) {
            System.err.println("Error: The specified path is not a directory.");
            System.exit(1);
        }
    }


    public void start(boolean loadJobs) {
        System.out.println("Starting Meteorological Data Processor...");

        // Start the directory monitoring thread
        directoryMonitorThread = new Thread(this::monitorDirectory);
        directoryMonitorThread.setName("DirectoryMonitor");
        directoryMonitorThread.start();

        // Start the job processor thread
        jobProcessorThread = new Thread(this::processJobs);
        jobProcessorThread.setName("JobProcessor");
        jobProcessorThread.start();

        // Start the CLI thread
        commandThread = new Thread(this::readCommands);
        commandThread.setName("CommandReader");
        commandThread.start();

        // Schedule periodic report
        scheduledService.scheduleAtFixedRate(this::generatePeriodicReport, 1, 1, TimeUnit.MINUTES);

        // Optionally load saved jobs
        if (loadJobs) {
            loadSavedJobs();
        }
    }

    // Directory monitoring thread
    private void monitorDirectory() {
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

    // Check for file changes in the directory
    private boolean checkForFileChanges() throws IOException {
        boolean changes = false;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath,
                path -> {
                    String fileName = path.getFileName().toString().toLowerCase();
                    return fileName.endsWith(".txt") || fileName.endsWith(".csv");
                })) {

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

    // Process all files in directory when changes are detected
    private void checkAndProcessDirectoryFiles() {
        try {
            // Clear the in-memory map before processing all files
            inMemoryMap.clear(); // ConcurrentHashMap's clear is thread-safe

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath,
                    path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".txt") || fileName.endsWith(".csv");
                    })) {

                for (Path file : stream) {
                    jobQueue.put(new ProcessFileJob(file));
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error processing directory files: " + e.getMessage());
        }
    }


    public void processFile(Path file) {
        String filePath = file.toString();

        // Check if file is already being processed
        if (!filesInUse.add(filePath)) {
            System.out.println("File " + file.getFileName() + " is already being processed, skipping.");
            return;
        }

        try {
            // Determine file size for chunking
            long fileSize = Files.size(file);
            long chunkSize = 10 * 1024 * 1024; // 10MB chunks
            int numChunks = (int) Math.ceil((double) fileSize / chunkSize);

            // Submit tasks for each chunk
            for (int i = 0; i < numChunks; i++) {
                final long startPosition = i * chunkSize;
                final long endPosition = Math.min(startPosition + chunkSize, fileSize);

                completionService.submit(() -> processFileChunk(file, startPosition, endPosition));
            }

            // Process results as they complete
            for (int i = 0; i < numChunks; i++) {
                try {
                    Future<Map<Character, StationData>> future = completionService.take();
                    Map<Character, StationData> chunkResult = future.get();

                    // Update the in-memory map directly with atomic operations
                    for (Map.Entry<Character, StationData> entry : chunkResult.entrySet()) {
                        char key = entry.getKey();
                        StationData newData = entry.getValue();

                        inMemoryMap.compute(key, (k, existingData) -> {
                            if (existingData == null) {
                                return newData;
                            } else {
                                existingData.update(newData.getStationCount(), newData.getTemperatureSum());
                                return existingData;
                            }
                        });
                    }
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println("Error processing chunk: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.err.println("Error reading file " + file.getFileName() + ". Continuing work.");
        } finally {
            // Mark the file as no longer in use
            filesInUse.remove(filePath);
        }
    }

    private Map<Character, StationData> processFileChunk(Path file, long startPosition, long endPosition) {
        Map<Character, StationData> localMap = new HashMap<>();
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

                // Faster parsing without using split
                int semicolonIndex = line.indexOf(';');
                if (semicolonIndex > 0 && semicolonIndex < line.length() - 1) {
                    String stationName = line.substring(0, semicolonIndex).trim();
                    if (!stationName.isEmpty()) {
                        try {
                            double temperature = Double.parseDouble(line.substring(semicolonIndex + 1).trim());
                            char firstLetter = Character.toLowerCase(stationName.charAt(0));

                            localMap.computeIfAbsent(firstLetter, k -> new StationData())
                                    .update(1, temperature);
                        } catch (NumberFormatException e) {
                            // Skip invalid temperature readings
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error processing chunk of file " + file.getFileName());
        }

        return localMap;
    }

    // Read commands from CLI
    private void readCommands() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Enter commands (type SHUTDOWN to exit):");

        while (isRunning.get()) {
            try {
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;

                String[] parts = input.split("\\s+", 2);
                String commandName = parts[0];
                Map<String, String> args = new HashMap<>();

                if (parts.length > 1) {
                    String argsString = parts[1];
                    String[] argsParts = argsString.split("\\s+");

                    for (int i = 0; i < argsParts.length; i++) {
                        if (argsParts[i].startsWith("--") || argsParts[i].startsWith("-")) {
                            String key = argsParts[i];
                            String value = (i + 1 < argsParts.length && !argsParts[i + 1].startsWith("-"))
                                    ? argsParts[++i] : "true";
                            args.put(key, value);
                        }
                    }
                }

                // Process the command
                handleCommand(commandName, args);

            } catch (Exception e) {
                System.err.println("Error processing command: " + e.getMessage());
            }
        }

        scanner.close();
        System.out.println("Command reader thread terminated.");
    }

    // Handle commands from CLI
    private void handleCommand(String commandName, Map<String, String> args) throws InterruptedException {
        try {
            switch (commandName) {
                case "SCAN":
                    handleScanCommand(args);
                    break;
                case "STATUS":
                    handleStatusCommand(args);
                    break;
                case "MAP":
                    jobQueue.put(new MapCommandJob());
                    break;
                case "EXPORTMAP":
                    jobQueue.put(new ExportMapCommandJob());
                    break;
                case "SHUTDOWN":
                    boolean saveJobs = args.containsKey("--save-jobs") || args.containsKey("-s");
                    jobQueue.put(new ShutdownCommandJob(saveJobs));
                    break;
                case "START":
                    boolean loadJobs = args.containsKey("--load-jobs") || args.containsKey("-l");
                    jobQueue.put(new StartCommandJob(loadJobs));
                    break;
                default:
                    System.err.println("Unknown command: " + commandName);
            }
        } catch (InterruptedException e) {
            System.err.println("Command interrupted: " + e.getMessage());
            throw e;
        }
    }

    // Process jobs from the job queue
    private void processJobs() {
        System.out.println("Job processor started");

        while (isRunning.get() || !jobQueue.isEmpty()) {
            try {
                Job job = jobQueue.take();

                // Check for poison pill
                if (job instanceof Job.PoisonPill) {
                    System.out.println("Received poison pill, job processor terminating...");
                    break;
                }

                job.execute(this);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error processing job: " + e.getMessage());
            }
        }

        System.out.println("Job processor thread terminated.");
    }

    // Handle SCAN command
    private void handleScanCommand(Map<String, String> args) throws InterruptedException {
        String minTemp = args.getOrDefault("--min", args.get("-m"));
        String maxTemp = args.getOrDefault("--max", args.get("-M"));
        String letter = args.getOrDefault("--letter", args.get("-l"));
        String output = args.getOrDefault("--output", args.get("-o"));
        String jobName = args.getOrDefault("--job", args.get("-j"));

        if (minTemp == null || maxTemp == null || letter == null || output == null || jobName == null) {
            System.err.println("Missing required arguments for SCAN command");
            return;
        }

        try {
            double min = Double.parseDouble(minTemp);
            double max = Double.parseDouble(maxTemp);

            if (min > max) {
                System.err.println("Min temperature cannot be greater than max temperature");
                return;
            }

            if (letter.length() != 1) {
                System.err.println("Letter must be a single character");
                return;
            }

            char targetLetter = Character.toLowerCase(letter.charAt(0));

            jobQueue.put(new ScanCommandJob(min, max, targetLetter, output, jobName));

        } catch (NumberFormatException e) {
            System.err.println("Invalid temperature format");
        }
    }

    // Execute SCAN command
    public void executeScanCommand(double min, double max, char targetLetter, String outputFile, String jobName) {
        Future<?> job = fileProcessorService.submit(() -> {
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

    // Scan files for temperature
    private void scanFilesForTemperature(double min, double max, char targetLetter, String outputFile) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath,
                    path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".txt") || fileName.endsWith(".csv");
                    })) {

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
                        // Process file in chunks
                        long fileSize = Files.size(file);
                        long chunkSize = 10 * 1024 * 1024; // 10MB chunks
                        int numChunks = (int) Math.ceil((double) fileSize / chunkSize);

                        List<Future<List<String>>> chunkResults = new ArrayList<>();

                        for (int i = 0; i < numChunks; i++) {
                            final long startPosition = i * chunkSize;
                            final long endPosition = Math.min(startPosition + chunkSize, fileSize);

                            Future<List<String>> result = fileProcessorService.submit(
                                    () -> scanFileChunk(file, startPosition, endPosition, min, max, targetLetter));

                            chunkResults.add(result);
                        }

                        // Process results as they complete
                        for (Future<List<String>> future : chunkResults) {
                            try {
                                List<String> matches = future.get();
                                for (String match : matches) {
                                    writer.println(match);
                                }
                            } catch (InterruptedException | ExecutionException e) {
                                System.err.println("Error during chunk scan: " + e.getMessage());
                            }
                        }
                    } finally {
                        filesInUse.remove(filePath);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error during scan operation: " + e.getMessage());
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

                String[] parts = line.split(";");
                if (parts.length == 2) {
                    String stationName = parts[0].trim();
                    if (!stationName.isEmpty()) {
                        try {
                            double temperature = Double.parseDouble(parts[1].trim());
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

    // Handle STATUS command
    private void handleStatusCommand(Map<String, String> args) throws InterruptedException {
        String jobName = args.getOrDefault("--job", args.get("-j"));

        if (jobName == null) {
            System.err.println("Missing job name for STATUS command");
            return;
        }

        jobQueue.put(new StatusCommandJob(jobName));
    }

    // Execute STATUS command
    public void executeStatusCommand(String jobName) {
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

    public void executeMapCommand() {
        if (inMemoryMap.isEmpty()) {
            System.out.println("Map is not yet available");
            return;
        }

        // Take a snapshot of the map to prevent concurrent modification issues during printing
        Map<Character, StationData> mapSnapshot = new HashMap<>(inMemoryMap);

        char[] alphabet = "abcdefghijklmnopqrstuvwxyz".toCharArray();
        for (int i = 0; i < alphabet.length; i += 2) {
            StationData data1 = mapSnapshot.getOrDefault(alphabet[i], new StationData());
            StationData data2 = (i + 1 < alphabet.length)
                    ? mapSnapshot.getOrDefault(alphabet[i + 1], new StationData())
                    : new StationData();

            System.out.printf("%c: %d - %.1f | %c: %d - %.1f%n",
                    alphabet[i], data1.getStationCount(), data1.getTemperatureSum(),
                    (i + 1 < alphabet.length) ? alphabet[i + 1] : ' ',
                    data2.getStationCount(), data2.getTemperatureSum());
        }
    }

    // Execute EXPORTMAP command
    public void executeExportMapCommand() {
        synchronized (exportLock) {
            exportMapToFile();
        }
    }

    // Export map to file
    private void exportMapToFile() {
        try {
            File logFile = new File("meteo_log.csv");
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile))) {
                writer.println("Letter,Station count,Sum");

                if (inMemoryMap.isEmpty()) {
                    System.out.println("Map is not yet available for export");
                    return;
                }

                // Take a snapshot of the map to prevent concurrent modification issues during export
                Map<Character, StationData> mapSnapshot = new HashMap<>(inMemoryMap);

                for (char c = 'a'; c <= 'z'; c++) {
                    StationData data = mapSnapshot.getOrDefault(c, new StationData());
                    writer.printf("%c,%d,%.0f%n", c, data.getStationCount(), data.getTemperatureSum());
                }

                System.out.println("Map exported to meteo_log.csv");
            }
        } catch (IOException e) {
            System.err.println("Error exporting map: " + e.getMessage());
        }
    }

    // Execute SHUTDOWN command
    public void executeShutdownCommand(boolean saveJobs) {
        System.out.println("Executing shutdown command...");

        if (saveJobs) {
            saveUnexecutedJobs();
        }

        isRunning.set(false);

        // Send poison pill to job processor
        try {
            jobQueue.put(new Job.PoisonPill());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Shutdown thread pools gracefully
        fileProcessorService.shutdown();
        scheduledService.shutdown();

        try {
            if (!fileProcessorService.awaitTermination(10, TimeUnit.SECONDS)) {
                fileProcessorService.shutdownNow();
            }

            if (!scheduledService.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduledService.shutdownNow();
            }
        } catch (InterruptedException e) {
            fileProcessorService.shutdownNow();
            scheduledService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Interrupt all threads if they're still running
        if (directoryMonitorThread != null && directoryMonitorThread.isAlive()) {
            directoryMonitorThread.interrupt();
        }

        if (jobProcessorThread != null && jobProcessorThread.isAlive()) {
            jobProcessorThread.interrupt();
        }

        System.out.println("Shutdown complete.");
    }

    // Save unexecuted jobs
    private void saveUnexecutedJobs() {
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

    // Execute START command
    public void executeStartCommand(boolean loadJobs) {
        if (loadJobs) {
            loadSavedJobs();
        }
    }

    // Load saved jobs
    private void loadSavedJobs() {
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
                    // In a real implementation, we would need to store job details to recreate them
                    System.out.println("Loaded job: " + jobName);
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading saved jobs: " + e.getMessage());
        }
    }

    // Generate periodic report
    private void generatePeriodicReport() {
        try {
            synchronized (exportLock) {
                System.out.println("Generating periodic report...");
                exportMapToFile();
            }
        } catch (Exception e) {
            System.err.println("Error generating periodic report: " + e.getMessage());
        }
    }
}