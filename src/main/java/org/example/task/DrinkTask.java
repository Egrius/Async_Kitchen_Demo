package org.example.task;

import org.example.dish.Dish;
import org.example.dish.Drink;
import org.example.dish.Pizza;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class DrinkTask extends CookingTask<Drink> {
    public DrinkTask(Drink drink, int orderId, ExecutorService assignedPool, boolean isVip) {
        super(drink, orderId, assignedPool, isVip);
    }

    @Override
    public CompletableFuture<Drink> start() {
        CompletableFuture<Drink> future = makeDrink(getDish(), getOrderId());

        super.setFuture(future);
        return future;
    }

    @Override
    public CompletableFuture<Drink> resume() {
        if(isInterrupted()) {
            setInterrupted(false);
            return start();
        }
        return CompletableFuture.failedFuture(new IllegalStateException("Task is not interrupted"));
    }

    private CompletableFuture<Drink> makeDrink(Drink drink, Integer orderId) {

        setRunning(true);

        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.printf("%n[заказ %d]: Начали готовить напиток '%s', id{%d} %n", orderId, drink.getName(), drink.getId());
                Thread.sleep(1000);
                System.out.printf("%n[заказ %d]: напиток '%s', ГОТОВ id{%d} %n", orderId, drink.getName(), drink.getId());
                drink.setReady(true);
                return drink;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, getAssignedPool());
    }

}
