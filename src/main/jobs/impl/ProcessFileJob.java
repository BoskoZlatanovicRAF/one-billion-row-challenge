package main.jobs.impl;

import main.jobs.Job;
import main.processors.MeteorologicalDataProcessor;

import java.nio.file.Path;

public class ProcessFileJob extends Job {
    private final Path filePath;

    public ProcessFileJob(Path filePath) {
        super("PROCESS_FILE");
        this.filePath = filePath;
    }

    public Path getFilePath() {
        return filePath;
    }

    @Override
    public void execute(MeteorologicalDataProcessor processor) {
        processor.processFile(filePath);
    }
}