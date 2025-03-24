package main.processors;

import main.commands.CommandParser;
import main.jobs.Job;
import main.jobs.impl.*;

import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandProcessor implements Runnable {
    private final BlockingQueue<Runnable> jobQueue;
    private final AtomicBoolean isRunning;
    private final CommandParser parser = new CommandParser();
    private final MeteorologicalDataProcessor processor;

    public CommandProcessor(BlockingQueue<Runnable> jobQueue, AtomicBoolean isRunning, MeteorologicalDataProcessor processor) {
        this.jobQueue = jobQueue;
        this.isRunning = isRunning;
        this.processor = processor;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Enter commands (type SHUTDOWN to exit):");

        while (isRunning.get()) {
            try {
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;

                Map.Entry<String, Map<String, String>> parsedCommand = parser.parseCommand(input);
                String commandName = parsedCommand.getKey();
                Map<String, String> args = parsedCommand.getValue();

                handleCommand(commandName, args);

            } catch (Exception e) {
                System.err.println("Error processing command: " + e.getMessage());
            }
        }

        scanner.close();
        System.out.println("Command reader thread terminated.");
    }

    private void handleCommand(String commandName, Map<String, String> args) throws InterruptedException {
        try {
            Runnable jobRunnable = createJobRunnable(commandName, args);
            if (jobRunnable != null) {
                jobQueue.put(jobRunnable);
            }
        } catch (InterruptedException e) {
            System.err.println("Command interrupted: " + e.getMessage());
            throw e;
        }
    }

    private Runnable createJobRunnable(String commandName, Map<String, String> args) {
        Job job = createJob(commandName, args);
        return job != null ? () -> job.execute(processor) : null;
    }

    private Job createJob(String commandName, Map<String, String> args) {
        switch (commandName) {
            case "SCAN":
                return createScanJob(args);
            case "STATUS":
                return createStatusJob(args);
            case "MAP":
                return createMapJob();
            case "EXPORTMAP":
                return createExportMapJob();
            case "SHUTDOWN":
                return createShutdownJob(args);
            case "START":
                return createStartJob(args);
            default:
                System.err.println("Unknown command: " + commandName);
                return null;
        }
    }

    private Job createScanJob(Map<String, String> args) {
        String minTemp = args.getOrDefault("--min", args.get("-m"));
        String maxTemp = args.getOrDefault("--max", args.get("-M"));
        String letter = args.getOrDefault("--letter", args.get("-l"));
        String output = args.getOrDefault("--output", args.get("-o"));
        String jobName = args.getOrDefault("--job", args.get("-j"));

        if (minTemp == null || maxTemp == null || letter == null || output == null || jobName == null) {
            System.err.println("Missing required arguments for SCAN command");
            return null;
        }

        try {
            double min = Double.parseDouble(minTemp);
            double max = Double.parseDouble(maxTemp);

            if (min > max) {
                System.err.println("Min temperature cannot be greater than max temperature");
                return null;
            }

            if (letter.length() != 1) {
                System.err.println("Letter must be a single character");
                return null;
            }

            char targetLetter = Character.toLowerCase(letter.charAt(0));

            return new ScanCommandJob(min, max, targetLetter, output, jobName);

        } catch (NumberFormatException e) {
            System.err.println("Invalid temperature format");
            return null;
        }
    }

    private Job createStatusJob(Map<String, String> args) {
        String jobName = args.getOrDefault("--job", args.get("-j"));

        if (jobName == null) {
            System.err.println("Missing job name for STATUS command");
            return null;
        }

        return new StatusCommandJob(jobName);
    }

    private Job createMapJob() {
        return new MapCommandJob();
    }

    private Job createExportMapJob() {
        return new ExportMapCommandJob();
    }

    private Job createShutdownJob(Map<String, String> args) {
        boolean saveJobs = args.containsKey("--save-jobs") || args.containsKey("-s");
        return new ShutdownCommandJob(saveJobs);
    }

    private Job createStartJob(Map<String, String> args) {
        boolean loadJobs = args.containsKey("--load-jobs") || args.containsKey("-l");
        return new StartCommandJob(loadJobs);
    }
}