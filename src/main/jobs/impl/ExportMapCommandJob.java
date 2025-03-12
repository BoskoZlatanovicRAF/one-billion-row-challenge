package main.jobs.impl;

import main.jobs.Job;
import main.processors.MeteorologicalDataProcessor;

public class ExportMapCommandJob extends Job {
    public ExportMapCommandJob() {
        super("EXPORTMAP");
    }

    @Override
    public void execute(MeteorologicalDataProcessor processor) {
        processor.executeExportMapCommand();
    }
}