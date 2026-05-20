package org.example.task;

import org.example.dish.Dish;
import org.example.dish.Pizza;
import org.example.dish.PizzaStage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class PizzaTask extends CookingTask<Pizza> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";

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
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s🚀 [PIZZA_START] Заказ #%d | Пицца '%s' id{%d} | Ретри: %d%s%n",
                BLUE, time, getOrderId(), getDish().getName(), getDish().getId(), retries, RESET);
        CompletableFuture<Pizza> future = makePizzaWithRetries(getDish(), retries, getOrderId());
        super.setFuture(future);
        return future;
    }

    @Override
    public void cancel() {
        super.cancel();
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s⚠️ [PIZZA_CANCEL] Заказ #%d | Пицца '%s' id{%d} | Статус: %s%s%n",
                YELLOW, time, getOrderId(), getDish().getName(), getDish().getId(), getPizzaStage(), RESET);
    }

    private CompletableFuture<Pizza> makePizzaWithRetries(Pizza pizza, int retriesLeft, Integer orderId) {
        return prepareDough(pizza, orderId)
                .thenCompose(v -> bakePizza(pizza, orderId))
                .exceptionallyCompose(throwable -> {
                    String time = LocalDateTime.now().format(TIME_FORMATTER);
                    if (throwable.getMessage() != null && throwable.getMessage().contains("подгорела")) {
                        System.out.printf("%s%s💀 [BURNT] Заказ #%d | Пицца '%s' id{%d} подгорела | retry: %d осталось%s%n",
                                RED, time, orderId, pizza.getName(), pizza.getId(), retriesLeft - 1, RESET);
                    }
                    if (retriesLeft <= 0) {
                        pizza.setReady(false);
                        pizzaStage = PizzaStage.FAILED;
                        System.out.printf("%s%s❌ [PIZZA_FAIL] Заказ #%d | Пиццу '%s' id{%d} не удалось приготовить (ретри закончились)%s%n",
                                RED, time, orderId, pizza.getName(), pizza.getId(), RESET);
                        throw new RuntimeException("[заказ %d]: 💥 Пиццу '%s' с id{%d} не удалось приготовить".formatted(orderId, pizza.getName(), pizza.getId()));
                    }
                    System.out.printf("%s%s🔄 [PIZZA_RETRY] Заказ #%d | Пицца '%s' id{%d} | Повторная попытка (%d осталось)%s%n",
                            YELLOW, time, orderId, pizza.getName(), pizza.getId(), retriesLeft - 1, RESET);
                    return makePizzaWithRetries(pizza, retriesLeft - 1, orderId);
                });
    }

    private CompletableFuture<Void> prepareDough(Dish dish, Integer orderId) {
        return CompletableFuture.runAsync(() -> {
            String time = LocalDateTime.now().format(TIME_FORMATTER);
            pizzaStage = PizzaStage.DOUGH;
            System.out.printf("%s%s🥣 [DOUGH_START] Заказ #%d | Пицца '%s' id{%d} | Замес теста...%s%n",
                    BLUE, time, orderId, dish.getName(), dish.getId(), RESET);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            System.out.printf("%s%s✅ [DOUGH_DONE] Заказ #%d | Пицца '%s' id{%d} | Тесто готово (1 сек)%s%n",
                    GREEN, time, orderId, dish.getName(), dish.getId(), RESET);
        });
    }

    private CompletableFuture<Pizza> bakePizza(Pizza pizza, Integer orderId) {
        setRunning(true);
        return CompletableFuture.supplyAsync(() -> {
            String time = LocalDateTime.now().format(TIME_FORMATTER);
            pizzaStage = PizzaStage.BAKING;
            System.out.printf("%s%s🔥 [BAKE_START] Заказ #%d | Пицца '%s' id{%d} | Выпечка в печи...%s%n",
                    BLUE, time, orderId, pizza.getName(), pizza.getId(), RESET);

            if (Math.random() <= PIZZA_FAIL_PERCENT) {
                pizzaStage = PizzaStage.FIRED;
                System.out.printf("%s%s💀 [BURNT] Заказ #%d | Пицца '%s' id{%d} подгорела в печи!%s%n",
                        RED, time, orderId, pizza.getName(), pizza.getId(), RESET);
                throw new RuntimeException("[заказ %d]: ❌ Пицца '%s', id{%d} подгорела".formatted(orderId, pizza.getName(), pizza.getId()));
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel();
                throw new RuntimeException(e);
            }
            pizza.setReady(true);
            pizzaStage = PizzaStage.DONE;
            System.out.printf("%s%s✨ [BAKE_DONE] Заказ #%d | Пицца '%s' id{%d} | Готово! (2 сек)%s%n",
                    GREEN, time, orderId, pizza.getName(), pizza.getId(), RESET);
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