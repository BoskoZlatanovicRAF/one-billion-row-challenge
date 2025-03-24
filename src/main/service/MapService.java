package main.service;

import main.data.StationData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MapService {
    private final Map<Character, StationData> inMemoryMap = new ConcurrentHashMap<>();
    private final Set<String> filesInUse = Collections.synchronizedSet(new HashSet<>());

    public MapService() {
    }

    public void clearMap() {
        inMemoryMap.clear();
    }

    public void updateMap(char key, StationData newData) {
        inMemoryMap.compute(key, (k, existingData) -> {
            if (existingData == null) {
                return newData;
            } else {
                existingData.update(newData.getStationCount(), newData.getTemperatureSum());
                return existingData;
            }
        });
    }

    public void displayMap() {
        if (inMemoryMap.isEmpty()) {
            System.out.println("Map is not yet available");
            return;
        }

        // Take a snapshot of the map to prevent concurrent modification issues
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

    public boolean isFileInUse(String filePath) {
        return filesInUse.contains(filePath);
    }

    public boolean markFileInUse(String filePath) {
        return filesInUse.add(filePath);
    }

    public void markFileNotInUse(String filePath) {
        filesInUse.remove(filePath);
    }

    public Set<String> getFilesInUse() {
        return filesInUse;
    }

    public Map<Character, StationData> getInMemoryMap() {
        return inMemoryMap;
    }
}