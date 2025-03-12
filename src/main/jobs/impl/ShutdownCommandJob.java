package main.jobs.impl;

import main.jobs.Job;
import main.processors.MeteorologicalDataProcessor;

public class ShutdownCommandJob extends Job {
    private final boolean saveJobs;

    public ShutdownCommandJob(boolean saveJobs) {
        super("SHUTDOWN");
        this.saveJobs = saveJobs;
    }

    @Override
    public void execute(MeteorologicalDataProcessor processor) {
        processor.executeShutdownCommand(saveJobs);
    }
}