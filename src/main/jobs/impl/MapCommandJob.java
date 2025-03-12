package main.jobs.impl;

import main.jobs.Job;
import main.processors.MeteorologicalDataProcessor;

public class MapCommandJob extends Job {
    public MapCommandJob() {
        super("MAP");
    }

    @Override
    public void execute(MeteorologicalDataProcessor processor) {
        processor.executeMapCommand();
    }
}