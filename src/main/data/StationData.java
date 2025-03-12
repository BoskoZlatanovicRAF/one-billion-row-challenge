package main.data;

public class StationData {
    private int stationCount;
    private double temperatureSum;

    public StationData() {
        this.stationCount = 0;
        this.temperatureSum = 0.0;
    }

    public synchronized void update(int count, double sum) {
        this.stationCount += count;
        this.temperatureSum += sum;
    }

    public int getStationCount() {
        return stationCount;
    }

    public double getTemperatureSum() {
        return temperatureSum;
    }
}