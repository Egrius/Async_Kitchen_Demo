package org.example.task;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.example.dish.Dish;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
/*
    Базовый класс, представляющий задачу, которая будет создаваться под каждое блюдо отдельного заказа.
 */
@Getter
@Setter
@ToString(exclude = {"future", "assignedPool"})
public abstract class CookingTask <T extends Dish> {
    protected final T dish;
    protected final int orderId;
    private final ExecutorService assignedPool;
    protected final boolean isVip;
    private volatile CompletableFuture<T> future;
    protected volatile boolean isRunning;
    protected volatile boolean cancelled;
    protected volatile boolean isStarted;
    protected final CountDownLatch startLatch;

    public CookingTask(T dish, int orderId, ExecutorService assignedPool, boolean isVip, CountDownLatch startLatch) {
        this.dish = dish;
        this.orderId = orderId;
        this.assignedPool = assignedPool;
        this.isVip = isVip;
        this.startLatch = startLatch;
    }

    public abstract CompletableFuture<T> start(); // здесь создается future и запускается задача

    protected void signalStarted() {
        if (startLatch != null) {
            startLatch.countDown();
        }
    }
}