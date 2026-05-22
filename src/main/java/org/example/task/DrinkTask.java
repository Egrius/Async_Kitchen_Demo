package org.example.task;

import org.example.dish.Drink;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

public class DrinkTask extends CookingTask<Drink> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";

    public DrinkTask(Drink drink, int orderId, ExecutorService assignedPool, boolean isVip, CountDownLatch startLatch) {
        super(drink, orderId, assignedPool, isVip, startLatch);
    }

    @Override
    public CompletableFuture<Drink> start() {

        setStarted(true);
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s🥤 [DRINK_START] Заказ #%d | Напиток '%s' id{%d}%s%n",
                BLUE, time, getOrderId(), getDish().getName(), getDish().getId(), RESET);
        CompletableFuture<Drink> future = makeDrink(getDish(), getOrderId());

        setRunning(true);
        startLatch.countDown();
        super.setFuture(future);

        return future;
    }

    private CompletableFuture<Drink> makeDrink(Drink drink, Integer orderId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000);
                drink.setReady(true);
                String time = LocalDateTime.now().format(TIME_FORMATTER);
                System.out.printf("%s%s✨ [DRINK_DONE] Заказ #%d | Напиток '%s' id{%d} готов (1 сек)%s%n",
                        GREEN, time, orderId, drink.getName(), drink.getId(), RESET);
                return drink;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, getAssignedPool());
    }
}