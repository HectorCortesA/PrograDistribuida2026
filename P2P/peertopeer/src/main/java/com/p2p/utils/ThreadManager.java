package com.p2p.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class ThreadManager {
    private final ExecutorService workerPool;
    private final ScheduledExecutorService scheduler;

    public ThreadManager() {
        this.workerPool = Executors.newCachedThreadPool();
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    public void executeTask(Runnable task) {
        workerPool.submit(task);
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public void shutdown() {
        workerPool.shutdown();
        scheduler.shutdown();
    }
}
