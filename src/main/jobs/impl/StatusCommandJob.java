package main.jobs.impl;

import main.jobs.Job;
import main.processors.MeteorologicalDataProcessor;

public class StatusCommandJob extends Job {
    private final String jobName;

    public StatusCommandJob(String jobName) {
        super("STATUS");
        this.jobName = jobName;
    }

    @Override
    public void execute(MeteorologicalDataProcessor processor) {
        processor.executeStatusCommand(jobName);
    }
}