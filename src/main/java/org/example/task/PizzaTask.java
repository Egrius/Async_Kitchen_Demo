package org.example.task;

import org.example.dish.Dish;
import org.example.dish.Pizza;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class PizzaTask extends CookingTask<Pizza> {

    private final int retries;

    public PizzaTask(Pizza dish, int orderId, ExecutorService assignedPool, boolean isVip, int retries) {
        super(dish, orderId, assignedPool, isVip);
        this.retries = retries;
    }

    @Override
    public CompletableFuture<Pizza> start() {
        CompletableFuture<Pizza> future = makePizzaWithRetries(getDish(), retries, getOrderId());
        setRunning(true);
        super.setFuture(future);
        return future;
    }

    @Override
    public CompletableFuture<Pizza> resume() {
        if(isInterrupted()) {
            setInterrupted(false);
            return start();
        } else {
            return CompletableFuture.failedFuture(new IllegalStateException("Task is not interrupted"));
        }
    }

    private CompletableFuture<Pizza> makePizzaWithRetries(Pizza pizza, int retries, Integer orderId) {

        return prepareDough(pizza, orderId).thenCompose(v -> bakePizza(pizza, orderId))
                .exceptionallyCompose(throwable -> {
                    System.out.println(throwable.getMessage());
                    if(retries <= 0) {
                        pizza.setReady(false);
                        throw new RuntimeException("[заказ %d]: 💥 Пиццу '%s' с id{%d} не удалось приготовить".formatted(orderId, pizza.getName(), pizza.getId()));
                    }
                    return makePizzaWithRetries(pizza, retries-1, orderId);
                });
    }

    // Для теста не будет пула, якобы его делают быстро
    private CompletableFuture<Void> prepareDough(Dish dish, Integer orderId) {
        return CompletableFuture.runAsync(() -> {
            System.out.printf("%n[заказ %d]: Начали замешивать тесто для пиццы '%s', id{%d}", orderId, dish.getName(), dish.getId());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            System.out.println("Тесто для пиццы готово");
        });
    }

    // TODO Здесь нужно сделать логику, когда задачу прервал вип поток, текущую задачу нужно будет сохранить в очередь на Kitchen
    private CompletableFuture<Pizza> bakePizza(Pizza pizza, Integer orderId) {
        return CompletableFuture.supplyAsync(() -> {
            if(Math.random() <= 0.7) {
                throw new RuntimeException("[заказ %d]: ❌ Пицца '%s', id{%d} подгорела".formatted(orderId, pizza.getName(), pizza.getId()));
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                // Здесь задачу прервали, значит она из пула уходит, её отменить нужно, а затем закинуть на кухню на восстановление
                cancel();
                // Затем текущую задачу нужно пробросить с исключением и забрать на кухне?
                throw new RuntimeException(e);
            }
            pizza.setReady(true);
            return pizza;
        }, getAssignedPool());
    }
}