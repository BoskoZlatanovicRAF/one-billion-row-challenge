package main.jobs;

import main.processors.MeteorologicalDataProcessor;

public abstract class Job {
    private final String type;

    public Job(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public abstract void execute(MeteorologicalDataProcessor processor);

    // Special job type for poison pill
    public static class PoisonPill extends Job {
        public PoisonPill() {
            super("POISON_PILL");
        }

        @Override
        public void execute(MeteorologicalDataProcessor processor) {
            // Does nothing, just signals the thread to terminate
        }
    }
}