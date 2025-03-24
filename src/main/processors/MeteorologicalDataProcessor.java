package main.processors;

import main.config.AppConfig;
import main.data.StationData;
import main.io.DirectoryMonitor;
import main.io.FileProcessor;
import main.service.MapService;
import main.service.ReportService;
import main.service.ScanService;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MeteorologicalDataProcessor {
    private final AppConfig config;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    // Services
    private final MapService mapService;
    private final ScanService scanService;
    private final ReportService reportService;

    // IO components
    private final DirectoryMonitor directoryMonitor;
    private final FileProcessor fileProcessor;

    // Processors
    private final CommandProcessor commandProcessor;
    private final JobProcessor jobProcessor;

    // Execution components
    private final BlockingQueue<Runnable> jobQueue = new LinkedBlockingQueue<>();
    private final ExecutorService fileProcessorService;
    private final ScheduledExecutorService scheduledService;

    // Threads
    private Thread directoryMonitorThread;
    private Thread jobProcessorThread;
    private Thread commandThread;

    public MeteorologicalDataProcessor(String directory) {
        this.config = new AppConfig(directory);

        // Initialize execution components
        this.fileProcessorService = Executors.newFixedThreadPool(config.getThreadPoolSize());
        this.scheduledService = Executors.newSingleThreadScheduledExecutor();

        // Initialize services
        this.mapService = new MapService();
        this.scanService = new ScanService(config, fileProcessorService, mapService.getFilesInUse());
        this.reportService = new ReportService(mapService);

        // Initialize IO components
        this.fileProcessor = new FileProcessor(config, fileProcessorService, mapService);
        this.directoryMonitor = new DirectoryMonitor(
                Paths.get(config.getDirectoryPath()),
                isRunning,
                fileProcessor,
                mapService
        );

        // Initialize processors
        this.jobProcessor = new JobProcessor(jobQueue, isRunning, this);
        this.commandProcessor = new CommandProcessor(jobQueue, isRunning, this);
    }

    public void start(boolean loadJobs) {
        System.out.println("Starting Meteorological Data Processor...");

        // Start the directory monitoring thread
        directoryMonitorThread = new Thread(directoryMonitor);
        directoryMonitorThread.setName("DirectoryMonitor");
        directoryMonitorThread.start();

        // Start the job processor thread
        jobProcessorThread = new Thread(jobProcessor);
        jobProcessorThread.setName("JobProcessor");
        jobProcessorThread.start();

        // Start the CLI thread
        commandThread = new Thread(commandProcessor);
        commandThread.setName("CommandReader");
        commandThread.start();

        // Schedule periodic report
        scheduledService.scheduleAtFixedRate(
                reportService::generatePeriodicReport,
                config.getReportIntervalMinutes(),
                config.getReportIntervalMinutes(),
                TimeUnit.MINUTES
        );

        // Optionally load saved jobs
        if (loadJobs) {
            loadSavedJobs();
        }
    }

    // Delegating methods for job execution
    public void processFile(Path file) {
        fileProcessor.processFile(file);
    }

    public void executeScanCommand(double min, double max, char letter, String outputFile, String jobName) {
        scanService.executeScan(min, max, letter, outputFile, jobName);
    }

    public void executeStatusCommand(String jobName) {
        scanService.checkJobStatus(jobName);
    }

    public void executeMapCommand() {
        mapService.displayMap();
    }

    public void executeExportMapCommand() {
        reportService.exportMapToFile();
    }

    public void executeShutdownCommand(boolean saveJobs) {
        System.out.println("Executing shutdown command...");

        if (saveJobs) {
            scanService.saveUnexecutedJobs();
        }

        isRunning.set(false);

        // Send poison pill to job processor
        try {
            jobQueue.put(() -> System.out.println("Poison pill received"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Shutdown thread pools gracefully
        fileProcessorService.shutdown();
        scheduledService.shutdown();

        try {
            if (!fileProcessorService.awaitTermination(10, TimeUnit.SECONDS)) {
                fileProcessorService.shutdownNow();
            }

            if (!scheduledService.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduledService.shutdownNow();
            }
        } catch (InterruptedException e) {
            fileProcessorService.shutdownNow();
            scheduledService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Interrupt all threads if they're still running
        if (directoryMonitorThread != null && directoryMonitorThread.isAlive()) {
            directoryMonitorThread.interrupt();
        }

        if (jobProcessorThread != null && jobProcessorThread.isAlive()) {
            jobProcessorThread.interrupt();
        }

        System.out.println("Shutdown complete.");
    }

    public void executeStartCommand(boolean loadJobs) {
        if (loadJobs) {
            loadSavedJobs();
        }
    }

    private void loadSavedJobs() {
        scanService.loadSavedJobs();
    }
}