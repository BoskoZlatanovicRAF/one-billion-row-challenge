package main.jobs.impl;

import main.jobs.Job;
import main.processors.MeteorologicalDataProcessor;

public class ScanCommandJob extends Job {
    private final double minTemp;
    private final double maxTemp;
    private final char letter;
    private final String outputFile;
    private final String jobName;

    public ScanCommandJob(double minTemp, double maxTemp, char letter, String outputFile, String jobName) {
        super("SCAN");
        this.minTemp = minTemp;
        this.maxTemp = maxTemp;
        this.letter = letter;
        this.outputFile = outputFile;
        this.jobName = jobName;
    }

    @Override
    public void execute(MeteorologicalDataProcessor processor) {
        processor.executeScanCommand(minTemp, maxTemp, letter, outputFile, jobName);
    }
}