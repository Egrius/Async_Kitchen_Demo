package org.example;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class CookingTask {
    private final CompletableFuture<Dish> future;
    private final Dish dish;
    private final int orderId;
    private final ExecutorService assignedPool;
    private volatile boolean isRunning; // выполняется прямо сейчас
    private volatile boolean isInterrupted; // прерван другим потоком

    public CookingTask(CompletableFuture<Dish> future, Dish dish, int orderId, ExecutorService assignedPool) {
        this.future = future;
        this.dish = dish;
        this.orderId = orderId;
        this.assignedPool = assignedPool;
    }

    public CompletableFuture<Dish> getFuture() {
        return future;
    }

    public Dish getDish() {
        return dish;
    }

    public int getOrderId() {
        return orderId;
    }

    public ExecutorService getAssignedPool() {
        return assignedPool;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(boolean running) {
        isRunning = running;
    }

    public boolean isInterrupted() {
        return isInterrupted;
    }

    public void setInterrupted(boolean interrupted) {
        isInterrupted = interrupted;
    }

    public void cancel() {
        this.isInterrupted = true;
        future.cancel(true); // прерываем поток
    }
}
