package main.io;

import main.config.AppConfig;
import main.data.StationData;
import main.service.MapService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class FileProcessor {
    private final AppConfig config;
    private final ExecutorService executorService;
    private final MapService mapService;
    private final ExecutorCompletionService<Map<Character, StationData>> completionService;

    public FileProcessor(AppConfig config, ExecutorService executorService, MapService mapService) {
        this.config = config;
        this.executorService = executorService;
        this.mapService = mapService;
        this.completionService = new ExecutorCompletionService<>(executorService);
    }

    public void processFile(Path file) {
        String filePath = file.toString();

        // Check if file is already being processed
        if (!mapService.markFileInUse(filePath)) {
            System.out.println("File " + file.getFileName() + " is already being processed, skipping.");
            return;
        }

        try {
            // Determine file size for chunking
            long fileSize = Files.size(file);
            long chunkSize = config.getChunkSize();
            int numChunks = (int) Math.ceil((double) fileSize / chunkSize);

            // Submit tasks for each chunk
            for (int i = 0; i < numChunks; i++) {
                final long startPosition = i * chunkSize;
                final long endPosition = Math.min(startPosition + chunkSize, fileSize);

                completionService.submit(() -> processFileChunk(file, startPosition, endPosition));
            }

            for (int i = 0; i < numChunks; i++) {
                try {
                    Future<Map<Character, StationData>> future = completionService.take();
                    Map<Character, StationData> chunkResult = future.get();

                    // Update the in-memory map directly with atomic operations
                    for (Map.Entry<Character, StationData> entry : chunkResult.entrySet()) {
                        mapService.updateMap(entry.getKey(), entry.getValue());
                    }
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println("Error processing chunk: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }
            }

        } catch (IOException e) {
            System.err.println("Error reading file " + file.getFileName() + ". Continuing work.");
        } finally {
            // Mark file as no longer in use
            mapService.markFileNotInUse(filePath);
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
}