package main.config;

public class AppConfig {
    private static final int DIRECTORY_POLL_INTERVAL_MS = 5000;
    private static final int DEFAULT_THREAD_POOL_SIZE = 4;
    private static final int REPORT_INTERVAL_MINUTES = 1;
    private static final long CHUNK_SIZE = 200 * 1024 * 1024; // 200MB chunks

    private final String directoryPath;

    public AppConfig(String directoryPath) {
        this.directoryPath = directoryPath;
    }

    public String getDirectoryPath() {
        return directoryPath;
    }

    public int getThreadPoolSize() {
        return DEFAULT_THREAD_POOL_SIZE;
    }

    public int getDirectoryPollIntervalMs() {
        return DIRECTORY_POLL_INTERVAL_MS;
    }

    public int getReportIntervalMinutes() {
        return REPORT_INTERVAL_MINUTES;
    }

    public long getChunkSize() {
        return CHUNK_SIZE;
    }
}