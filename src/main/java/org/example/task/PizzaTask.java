package org.example.task;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.example.dish.Dish;
import org.example.dish.Pizza;
import org.example.dish.PizzaStage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

@Getter
@Setter
public class PizzaTask extends CookingTask<Pizza> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";

    @Override
    public String toString() {
        return "PizzaTask{" +
                "retries=" + retries +
                ", PIZZA_FAIL_PERCENT=" + PIZZA_FAIL_PERCENT +
                ", pizzaStage=" + pizzaStage +
                ", dish=" + dish +
                ", orderId=" + orderId +
                ", isVip=" + isVip +
                ", isRunning=" + isRunning +
                ", cancelled=" + cancelled +
                ", isStarted=" + isStarted +
                '}';
    }

    private final int retries;
    private final double PIZZA_FAIL_PERCENT = 0.3;
    private volatile PizzaStage pizzaStage = PizzaStage.NONE;

    public PizzaTask(Pizza dish, int orderId, ExecutorService assignedPool, boolean isVip, int retries, CountDownLatch startLatch) {
        super(dish, orderId, assignedPool, isVip, startLatch);
        this.retries = retries;
    }

    @Override
    public CompletableFuture<Pizza> start() {

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s🚀 [PIZZA_START] Заказ #%d | Пицца '%s' id{%d} | Ретри: %d%s%n",
                BLUE, time, getOrderId(), getDish().getName(), getDish().getId(), retries, RESET);

        if (isCancelled()) {
            System.out.println("ПИЦЦА БЫЛА ОТМЕНЕНА ПЕРЕД СТАРТОМ");
            return CompletableFuture.completedFuture(getDish());
        }

        CompletableFuture<Pizza> future = makePizzaWithRetries(getDish(), retries, getOrderId());

        super.setFuture(future);
        setStarted(true);
        setRunning(true);
        startLatch.countDown();

        return future;
    }

    /*
    Продолжить прерваное выполнение
     */
    public CompletableFuture<Pizza> resume() {
        setCancelled(false);
        setRunning(true);
        CompletableFuture<Pizza> resumedFuture;
        switch (pizzaStage) {
            case NONE -> resumedFuture = start();
            case DOUGH -> resumedFuture = prepareDough(getDish(), getOrderId())
                    .thenCompose(p -> bakePizza(getDish(), getOrderId()));
            case BAKING -> resumedFuture = bakePizza(getDish(), getOrderId());
            default -> resumedFuture = CompletableFuture.completedFuture(getDish());
        }
        super.setFuture(resumedFuture);
        return resumedFuture;
    }

    /*
    Метод отмены, для того чтобы освободить под випа.
    Суть: установить флаг отмены для кооперативного вытеснения. Future.cancel() не подходит под данную задачу.
     */
    public void cancel() {

        if (pizzaStage == PizzaStage.DONE) {
            return;
        }

        setRunning(false);
        setCancelled(true);

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
                        setRunning(false);
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

    private CompletableFuture<Pizza> prepareDough(Pizza pizza, Integer orderId) {
        return CompletableFuture.supplyAsync(() -> {

            if(isCancelled()) {

                System.out.println("ПРЕРВАНО ПЕРЕД ЗАМЕШИВАНИЕМ ТЕСТА");
                return pizza;
            }

            pizzaStage = PizzaStage.DOUGH;

            String time = LocalDateTime.now().format(TIME_FORMATTER);
            System.out.printf("%s%s🥣 [DOUGH_START] Заказ #%d | Пицца '%s' id{%d} | Замес теста...%s%n",
                    BLUE, time, orderId, pizza.getName(), pizza.getId(), RESET);

            try {
                for (int i = 0; i < 2; i++) {
                    Thread.sleep(100);

                    if (isCancelled()) {
                        System.out.println("ПРЕРВАНО В ПРОЦЕССЕ ЗАМЕШИВАНИЯ ТЕСТА");
                        return pizza;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel(); // Для уверенности
                return pizza;
            }
            System.out.printf("%s%s✅ [DOUGH_DONE] Заказ #%d | Пицца '%s' id{%d} | Тесто готово (1 сек)%s%n",
                    GREEN, time, orderId, pizza.getName(), pizza.getId(), RESET);
            return pizza;
        });
    }

    private CompletableFuture<Pizza> bakePizza(Pizza pizza, Integer orderId) {

        return CompletableFuture.supplyAsync(() -> {

            if(isCancelled()) {
                System.out.println("ПРЕРВАНО ПЕРЕД ВЫПЕКАНИЕМ ПИЦЦЫ");
                return pizza;
            }

            pizzaStage = PizzaStage.BAKING;

            String time = LocalDateTime.now().format(TIME_FORMATTER);
            System.out.printf("%s%s🔥 [BAKE_START] Заказ #%d | Пицца '%s' id{%d} | Выпечка в печи...%s%n",
                    BLUE, time, orderId, pizza.getName(), pizza.getId(), RESET);

            try {
                for (int i = 0; i < 2; i++) {
                    Thread.sleep(200);

                    if (isCancelled()) {
                        System.out.println("ПРЕРВАНО В ПРОЦЕССЕ ВЫПЕКАНИЯ ПИЦЦЫ (БУКВАЛЬНО ДОСТАЛИ ИЗ ПЕЧКИ)");
                        return pizza;
                    }
                }

                if (Math.random() <= PIZZA_FAIL_PERCENT) {
                    pizzaStage = PizzaStage.FIRED;
                    System.out.printf("%s%s💀 [BURNT] Заказ #%d | Пицца '%s' id{%d} подгорела в печи!%s%n",
                            RED, time, orderId, pizza.getName(), pizza.getId(), RESET);
                    throw new RuntimeException("[заказ %d]: ❌ Пицца '%s', id{%d} подгорела".formatted(orderId, pizza.getName(), pizza.getId()));
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel(); // для уверенности
                throw new RuntimeException(e);
            }

            pizza.setReady(true);
            pizzaStage = PizzaStage.DONE;

            System.out.printf("%s%s✨ [BAKE_DONE] Заказ #%d | Пицца '%s' id{%d} | Готово! (2 сек)%s%n",
                    GREEN, time, orderId, pizza.getName(), pizza.getId(), RESET);


            setRunning(false);
            return pizza;
        }, getAssignedPool());
    }

}