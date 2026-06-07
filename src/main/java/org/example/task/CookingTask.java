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
@ToString(exclude = {"cookingFuture", "assignedPool"})
public abstract class CookingTask <T extends Dish> {
    protected final int orderId;
    protected final boolean isVip;
    protected final T dish;

    private final ExecutorService assignedPool;

    private volatile CompletableFuture<T> cookingFuture;
    private CompletableFuture<T> resultFuture;

    private final CountDownLatch startLatch;

    private volatile TaskState taskState;

    public CookingTask(T dish, int orderId, ExecutorService assignedPool, boolean isVip, CountDownLatch startLatch) {
        this.dish = dish;
        this.orderId = orderId;
        this.assignedPool = assignedPool;
        this.isVip = isVip;
        this.startLatch = startLatch;
        taskState = TaskState.CREATED;

        resultFuture = new CompletableFuture<>();

    }

    public abstract CompletableFuture<T> start(); // здесь создается future и запускается задача

    protected void signalStarted() {
        if (startLatch != null) {
            startLatch.countDown();
        }
    }
}