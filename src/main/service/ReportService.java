package main.service;

import main.data.StationData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

public class ReportService {
    private final MapService mapService;
    private final Object exportLock = new Object();

    public ReportService(MapService mapService) {
        this.mapService = mapService;
    }

    public void generatePeriodicReport() {
        try {
            synchronized (exportLock) {
                System.out.println("Generating periodic report...");
                exportMapToFile();
            }
        } catch (Exception e) {
            System.err.println("Error generating periodic report: " + e.getMessage());
        }
    }

    public void exportMapToFile() {
        synchronized (exportLock) {
            try {
                File logFile = new File("meteo_log.csv");
                try (PrintWriter writer = new PrintWriter(new FileWriter(logFile))) {
                    writer.println("Letter,Station count,Sum");

                    Map<Character, StationData> inMemoryMap = mapService.getInMemoryMap();
                    if (inMemoryMap.isEmpty()) {
                        System.out.println("Map is not yet available for export");
                        return;
                    }

                    // Take a snapshot of the map to prevent concurrent modification issues
                    Map<Character, StationData> mapSnapshot = new HashMap<>(inMemoryMap);

                    for (char c = 'a'; c <= 'z'; c++) {
                        StationData data = mapSnapshot.getOrDefault(c, new StationData());
                        writer.printf("%c,%d,%.1f%n", c, data.getStationCount(), data.getTemperatureSum());
                    }

                    System.out.println("Map exported to meteo_log.csv");
                }
            } catch (IOException e) {
                System.err.println("Error exporting map: " + e.getMessage());
            }
        }
    }
}