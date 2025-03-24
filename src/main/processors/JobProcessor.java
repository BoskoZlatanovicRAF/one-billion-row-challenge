// src/main/processors/JobProcessor.java
package main.processors;

import main.jobs.Job;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class JobProcessor implements Runnable {
    private final BlockingQueue<Runnable> jobQueue;
    private final AtomicBoolean isRunning;
    private final MeteorologicalDataProcessor processor;

    public JobProcessor(BlockingQueue<Runnable> jobQueue, AtomicBoolean isRunning,
                        MeteorologicalDataProcessor processor) {
        this.jobQueue = jobQueue;
        this.isRunning = isRunning;
        this.processor = processor;
    }

    @Override
    public void run() {
        System.out.println("Job processor started");

        while (isRunning.get() || !jobQueue.isEmpty()) {
            try {
                Runnable job = jobQueue.take();

                // Check if this is a poison pill job
                if (job instanceof Job.PoisonPill) {
                    System.out.println("Received poison pill, job processor terminating...");
                    break;
                }

                // Execute the job
                job.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error processing job: " + e.getMessage());
            }
        }

        System.out.println("Job processor thread terminated.");
    }
}