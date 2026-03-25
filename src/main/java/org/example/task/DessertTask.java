package org.example.task;

import org.example.dish.Dessert;
import org.example.dish.Dish;
import org.example.dish.Drink;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class DessertTask extends CookingTask<Dessert> {
    public DessertTask(Dessert dish, int orderId, ExecutorService assignedPool, boolean isVip) {
        super(dish, orderId, assignedPool, isVip);
    }

    @Override
    public CompletableFuture<Dessert> start() {
        CompletableFuture<Dessert> future = makeDessert(getDish(), getOrderId());

        setRunning(true);
        super.setFuture(future);
        return future;
    }

    @Override
    public CompletableFuture<Dessert> resume() {
        if(isInterrupted()) {
            setInterrupted(false);
            return start();
        }
        return CompletableFuture.failedFuture(new IllegalStateException("Task is not interrupted"));
    }

    private CompletableFuture<Dessert> makeDessert(Dessert dessert, Integer orderId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.printf("%n[заказ %d]: Начали готовить десерт '%s', id{%d} %n", orderId, dessert.getName(), dessert.getId());
                Thread.sleep(1000);
                System.out.printf("%n[заказ %d]: десерт '%s', ГОТОВ id{%d} %n", orderId, dessert.getName(), dessert.getId());
                dessert.setReady(true);
                return dessert;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, getAssignedPool());
    }
}
