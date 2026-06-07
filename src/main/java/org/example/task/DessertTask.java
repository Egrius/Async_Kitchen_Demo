package org.example.task;

import org.example.dish.Dessert;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

public class DessertTask extends CookingTask<Dessert> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";

    public DessertTask(Dessert dish, int orderId, ExecutorService assignedPool, boolean isVip, CountDownLatch startLatch) {
        super(dish, orderId, assignedPool, isVip, startLatch);
    }

    @Override
    public CompletableFuture<Dessert> start() {

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s🍰 [DESSERT_START] Заказ #%d | Десерт '%s' id{%d}%s%n",
                BLUE, time, getOrderId(), getDish().getName(), getDish().getId(), RESET);

        CompletableFuture<Dessert> cookingFuture = makeDessert(getDish(), getOrderId());
        super.setCookingFuture(cookingFuture);

        signalStarted();

        return getResultFuture();
    }

    private CompletableFuture<Dessert> makeDessert(Dessert dessert, Integer orderId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000);

                String time = LocalDateTime.now().format(TIME_FORMATTER);
                System.out.printf("%s%s✨ [DESSERT_DONE] Заказ #%d | Десерт '%s' id{%d} готов (1 сек)%s%n",
                        GREEN, time, orderId, dessert.getName(), dessert.getId(), RESET);

                getResultFuture().complete(getDish());
                return dessert;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                getResultFuture().completeExceptionally(e);
                throw new RuntimeException(e);
            }
        }, getAssignedPool());
    }
}