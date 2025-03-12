package main.jobs.impl;

import main.jobs.Job;
import main.processors.MeteorologicalDataProcessor;

public class StartCommandJob extends Job {
    private final boolean loadJobs;

    public StartCommandJob(boolean loadJobs) {
        super("START");
        this.loadJobs = loadJobs;
    }

    @Override
    public void execute(MeteorologicalDataProcessor processor) {
        processor.executeStartCommand(loadJobs);
    }
}