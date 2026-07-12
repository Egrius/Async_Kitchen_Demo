package org.example.task;

import org.example.dish.Drink;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Задача приготовления напитка.
 * <p>Напиток готовится асинхронно в выделенном пуле {@link org.example.KitchenService#drinkPool}.
 * Время приготовления фиксировано — 1 секунда (имитация).</p>
 *
 * <p><b>Особенности:</b>
 * <ul>
 *   <li>Не поддерживает повторные попытки</li>
 *   <li>Не вытесняется VIP-заказами</li>
 *   <li>При прерывании потока задача завершается с ошибкой</li>
 * </ul>
 * </p>
 *
 * @author Egrius
 * @see CookingTask
 * @see DessertTask
 * @see PizzaTask
 */
public class DrinkTask extends CookingTask<Drink> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";

    /**
     * Конструктор задачи напитка.
     *
     * @param drink        напиток для приготовления
     * @param orderId      идентификатор заказа
     * @param assignedPool пул потоков (должен быть drinkPool)
     * @param isVip        флаг VIP-заказа (только для логирования)
     */
    public DrinkTask(Drink drink, int orderId, ExecutorService assignedPool,
                     boolean isVip) {
        super(drink, orderId, assignedPool, isVip);
    }

    /**
     * Запускает приготовление напитка.
     * <p>Логирует старт, создаёт асинхронную задачу через {@link #makeDrink},
     * сохраняет future и уведомляет через {@code startLatch}.</p>
     *
     * @return {@code CompletableFuture}, который завершится готовым напитком или ошибкой
     */
    @Override
    public CompletableFuture<Drink> start() {
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s🥤 [DRINK_START] Заказ #%d | Напиток '%s' id{%d}%s%n",
                BLUE, time, getOrderId(), getDish().getName(), getDish().getId(), RESET);

        CompletableFuture<Drink> cookingFuture = makeDrink(getDish(), getOrderId());
        super.setCookingFuture(cookingFuture);

        return getResultFuture();
    }

    /**
     * Асинхронное приготовление напитка.
     * <p>Имитирует работу с задержкой 1 секунда. При успехе завершает {@link #getResultFuture()}
     * и возвращает напиток. При прерывании потока — завершает future с ошибкой.</p>
     *
     * @param drink   напиток для приготовления
     * @param orderId идентификатор заказа (для логов)
     * @return {@code CompletableFuture} с результатом приготовления
     */
    private CompletableFuture<Drink> makeDrink(Drink drink, Integer orderId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000);

                String time = LocalDateTime.now().format(TIME_FORMATTER);
                System.out.printf("%s%s✨ [DRINK_DONE] Заказ #%d | Напиток '%s' id{%d} готов (1 сек)%s%n",
                        GREEN, time, orderId, drink.getName(), drink.getId(), RESET);

                getResultFuture().complete(getDish());
                return drink;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                getResultFuture().completeExceptionally(e);
                throw new RuntimeException(e);
            }
        }, getAssignedPool());
    }
}