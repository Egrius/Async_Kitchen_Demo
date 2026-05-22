package org.example.task;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.example.dish.Dish;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
/*
    Базовый класс, представляющий задачу, которая будет создаваться под каждое блюдо отдельного заказа.
 */
@Getter
@Setter
public abstract class CookingTask <T extends Dish> {
    private final T dish;
    private final int orderId;
    private final ExecutorService assignedPool;
    private final boolean isVip;
    private volatile CompletableFuture<T> future; // теперь не final
    private volatile boolean isRunning;
    private volatile boolean cancelled;
    private volatile boolean isStarted;

    public CookingTask(T dish, int orderId, ExecutorService assignedPool, boolean isVip) {
        this.dish = dish;
        this.orderId = orderId;
        this.assignedPool = assignedPool;
        this.isVip = isVip;
    }

    public abstract CompletableFuture<T> start(); // здесь создается future и запускается задача

}