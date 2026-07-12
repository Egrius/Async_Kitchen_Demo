package org.example.task;

import org.example.dish.Dessert;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Задача приготовления десерта.
 * <p>Десерт готовится асинхронно в выделенном пуле {@link org.example.KitchenService#dessertPool}.
 * Время приготовления фиксировано — 1 секунда (имитация).</p>
 *
 * <p><b>Особенности:</b>
 * <ul>
 *   <li>Не поддерживает повторные попытки (десерт не может "подгореть")</li>
 *   <li>Не подлежит вытеснению VIP-заказами (только пицца имеет приоритеты)</li>
 *   <li>При прерывании потока (InterruptedException) задача завершается с ошибкой</li>
 * </ul>
 * </p>
 *
 * @author Egrius
 * @see CookingTask
 * @see DrinkTask
 * @see PizzaTask
 */
public class DessertTask extends CookingTask<Dessert> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";

    /**
     * Конструктор задачи десерта.
     *
     * @param dish         десерт для приготовления
     * @param orderId      идентификатор заказа
     * @param assignedPool пул потоков (должен быть dessertPool)
     * @param isVip        флаг VIP-заказа (влияет только на логирование)
     */
    public DessertTask(Dessert dish, int orderId, ExecutorService assignedPool,
                       boolean isVip) {
        super(dish, orderId, assignedPool, isVip);
    }

    /**
     * Запускает приготовление десерта.
     * <p>Логирует старт, создаёт асинхронную задачу через {@link #makeDessert},
     * сохраняет future в {@code cookingFuture} и уведомляет через {@code startLatch}.</p>
     *
     * @return {@code CompletableFuture}, который завершится готовым десертом или ошибкой
     */
    @Override
    public CompletableFuture<Dessert> start() {
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s🍰 [DESSERT_START] Заказ #%d | Десерт '%s' id{%d}%s%n",
                BLUE, time, getOrderId(), getDish().getName(), getDish().getId(), RESET);

        CompletableFuture<Dessert> cookingFuture = makeDessert(getDish(), getOrderId());
        super.setCookingFuture(cookingFuture);

        return getResultFuture();
    }

    /**
     * Асинхронное приготовление десерта.
     * <p>Имитирует работу с задержкой 1 секунда. При успехе завершает {@link #getResultFuture()}
     * и возвращает десерт. При прерывании потока — завершает future с ошибкой.</p>
     *
     * @param dessert десерт для приготовления
     * @param orderId идентификатор заказа (для логов)
     * @return {@code CompletableFuture} с результатом приготовления
     */
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