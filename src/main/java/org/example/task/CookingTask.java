package org.example.task;

import org.example.dish.Dish;
import org.example.dish.Pizza;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
/*
    Базовый класс, представляющий задачу, которая будет создаваться под каждое блюдо отдельного заказа.
 */
public abstract class CookingTask <T extends Dish> {
    private final T dish;
    private final int orderId;
    private final ExecutorService assignedPool;
    private final boolean isVip;
    private volatile CompletableFuture<T> future; // теперь не final
    private volatile boolean isRunning;
    private volatile boolean isInterrupted;

    public CookingTask(T dish, int orderId, ExecutorService assignedPool, boolean isVip) {
        this.dish = dish;
        this.orderId = orderId;
        this.assignedPool = assignedPool;
        this.isVip = isVip;
    }

    public abstract CompletableFuture<T> start(); // здесь создается future и запускается задача
    public abstract  CompletableFuture<T> resume(); // здесь пересоздается future

    public void cancel() {
        this.isInterrupted = true;
        this.isRunning = false;
        if (future != null) {
            future.cancel(true);
        }
    }

    public CompletableFuture<T> getFuture() { return future; }

    public T getDish() {
        return dish;
    }

    public int getOrderId() {
        return orderId;
    }

    public ExecutorService getAssignedPool() {
        return assignedPool;
    }

    public boolean isVip() {
        return isVip;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean isInterrupted() {
        return isInterrupted;
    }

    public void setFuture(CompletableFuture<T> future) {
        this.future = future;
    }

    public void setRunning(boolean running) {
        isRunning = running;
    }

    public void setInterrupted(boolean interrupted) {
        isInterrupted = interrupted;
    }
}