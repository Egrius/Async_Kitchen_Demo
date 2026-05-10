package org.example.task;

import org.example.dish.Dish;
import org.example.dish.Pizza;
import org.example.dish.PizzaStage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class PizzaTask extends CookingTask<Pizza> {

    private final int retries;
    private final double PIZZA_FAIL_PERCENT = 0.3;
    private volatile PizzaStage pizzaStage = PizzaStage.NONE;

    public PizzaTask(Pizza dish, int orderId, ExecutorService assignedPool, boolean isVip, int retries) {
        super(dish, orderId, assignedPool, isVip);
        this.retries = retries;
    }

    @Override
    public CompletableFuture<Pizza> start() {
        isStarted = true;
        CompletableFuture<Pizza> future = makePizzaWithRetries(getDish(), retries, getOrderId());

        super.setFuture(future);
        return future;
    }

    @Override
    public void cancel() {
        super.cancel();
        System.out.printf("__ [Заказ %d]: ПРИГОТОВЛЕНИЕ БЛЮДА '%s' ПРЕРВАНО, СТАТУС ГОТОВКИ: %s",
                super.getOrderId(), getDish(), getPizzaStage());
    }

    private CompletableFuture<Pizza> makePizzaWithRetries(Pizza pizza, int retries, Integer orderId) {

        return prepareDough(pizza, orderId)
                .thenCompose(v -> bakePizza(pizza, orderId))
                .exceptionallyCompose(throwable -> {
                    System.out.println(throwable.getMessage());
                    if(retries <= 0) {
                        pizza.setReady(false);
                        pizzaStage = PizzaStage.FAILED;
                        throw new RuntimeException("[заказ %d]: 💥 Пиццу '%s' с id{%d} не удалось приготовить".formatted(orderId, pizza.getName(), pizza.getId()));
                    }
                    return makePizzaWithRetries(pizza, retries-1, orderId);
                });
    }

    // Для теста не будет пула, якобы его делают быстро
    private CompletableFuture<Void> prepareDough(Dish dish, Integer orderId) {
        return CompletableFuture.runAsync(() -> {
            pizzaStage = PizzaStage.DOUGH;
            System.out.printf("%n[заказ %d]: Начали замешивать тесто для пиццы '%s', id{%d}", orderId, dish.getName(), dish.getId());
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            System.out.printf("\"%n[заказ %d]: Тесто для пиццы '%s' готово", orderId, dish.getName());
        });
    }

    // TODO Здесь нужно сделать логику, когда задачу прервал вип поток, текущую задачу нужно будет сохранить в очередь на Kitchen
    private CompletableFuture<Pizza> bakePizza(Pizza pizza, Integer orderId) {

        setRunning(true);

        return CompletableFuture.supplyAsync(() -> {
            pizzaStage = PizzaStage.BAKING;
            if(Math.random() <= PIZZA_FAIL_PERCENT) {
                pizzaStage = PizzaStage.FIRED;
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
            pizzaStage = PizzaStage.DONE;
            return pizza;
        }, getAssignedPool());
    }

    public PizzaStage getPizzaStage() {
        return pizzaStage;
    }

    public void setPizzaStage(PizzaStage pizzaStage) {
        this.pizzaStage = pizzaStage;
    }
}