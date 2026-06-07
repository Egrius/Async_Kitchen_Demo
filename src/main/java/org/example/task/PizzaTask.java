package org.example.task;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.example.KitchenService;
import org.example.dish.Dish;
import org.example.dish.Pizza;
import org.example.dish.PizzaStage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

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
                ", PIZZA_FAIL_PERCENT=" + PIZZA_FAIL_PERCENT +
                ", pizzaStage=" + pizzaStage +
                ", dish=" + dish +
                ", orderId=" + orderId +
                ", isVip=" + isVip +
                '}';
    }

    private final AtomicInteger remainingRetries;
    private final double PIZZA_FAIL_PERCENT = 0.3;
    private volatile PizzaStage pizzaStage = PizzaStage.NONE;

    public PizzaTask(Pizza dish, int orderId, ExecutorService assignedPool, boolean isVip, int retries, CountDownLatch startLatch) {
        super(dish, orderId, assignedPool, isVip, startLatch);

        remainingRetries = new AtomicInteger(retries);
    }

    @Override
    public CompletableFuture<Pizza> start() {

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s🚀 [PIZZA_START] Заказ #%d | Пицца '%s' id{%d} | Ретри: %d%s%n",
                BLUE, time, getOrderId(), getDish().getName(), getDish().getId(), remainingRetries.get(), RESET);

        if (getTaskState() == TaskState.DISPLACED) {
            System.out.println("ПИЦЦА БЫЛА ОТМЕНЕНА ПЕРЕД СТАРТОМ");
            return getResultFuture();
        }

        CompletableFuture<Pizza> future = makePizzaWithRetries(getDish(), getOrderId());

        super.setCookingFuture(future);

        signalStarted();

        return getResultFuture(); // Пока временно, вообще смысла не имеет возвращать что-то
    }

    /*
    Продолжить прерваное выполнение
     */
    public CompletableFuture<Pizza> resume() {
        setTaskState(TaskState.RUNNING);

        CompletableFuture<Pizza> resumedFuture;

        switch (pizzaStage) {
            case NONE -> resumedFuture = start();
            case DOUGH -> resumedFuture = makePizzaWithRetries(getDish(), getOrderId());
            case BAKING -> resumedFuture = runFutureTaskAndReturnPizzaResult(bakePizza(getDish(), getOrderId()));

            default -> resumedFuture = CompletableFuture.completedFuture(getDish());
        }
        super.setCookingFuture(resumedFuture);

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

       setTaskState(TaskState.DISPLACED);

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s⚠️ [PIZZA_CANCEL] Заказ #%d | Пицца '%s' id{%d} | Статус: %s%s%n",
                YELLOW, time, getOrderId(), getDish().getName(), getDish().getId(), getPizzaStage(), RESET);
    }

    private CompletableFuture<Pizza> runFutureTaskAndReturnPizzaResult(CompletableFuture<Pizza> futureToComplete) {
        return futureToComplete
                .exceptionallyCompose((throwable) -> {

                    Pizza pizza = getDish();
                    String time = LocalDateTime.now().format(TIME_FORMATTER);

                    if (throwable.getMessage() != null && !throwable.getMessage().contains("подгорела")) {

                        getResultFuture().completeExceptionally(throwable);
                        return CompletableFuture.failedFuture(throwable);
                    }

                    int newRetries = remainingRetries.decrementAndGet();

                    System.out.printf("%s%s💀 [BURNT] Заказ #%d | Пицца '%s' id{%d} подгорела | retry: %d осталось%s%n",
                            RED, time, orderId, pizza.getName(), pizza.getId(), newRetries, RESET);
                    setPizzaStage(PizzaStage.FIRED);

                    if (newRetries  <= 0) {

                        setTaskState(TaskState.FAILED);
                        pizzaStage = PizzaStage.FAILED;

                        System.out.printf("%s%s❌ [PIZZA_FAIL] Заказ #%d | Пиццу '%s' id{%d} не удалось приготовить (ретри закончились)%s%n",
                                RED, time, orderId, pizza.getName(), pizza.getId(), RESET);

                        getResultFuture().completeExceptionally(throwable); // горячий фьючер

                        RuntimeException error = new RuntimeException(
                                "[заказ %d]: 💥 Пиццу '%s' с id{%d} не удалось приготовить"
                                        .formatted(orderId, pizza.getName(), pizza.getId()));

                        getResultFuture().completeExceptionally(error);
                        return CompletableFuture.failedFuture(error);
                    }

                    System.out.printf("%s%s🔄 [PIZZA_RETRY] Заказ #%d | Пицца '%s' id{%d} | Повторная попытка (%d осталось)%s%n",
                            YELLOW, time, orderId, pizza.getName(), pizza.getId(), newRetries, RESET);

                    return runFutureTaskAndReturnPizzaResult(bakePizza(pizza, orderId));

                });
    }

    private CompletableFuture<Pizza> makePizzaWithRetries(Pizza pizza, Integer orderId) {
        return runFutureTaskAndReturnPizzaResult(
                prepareDough(pizza, orderId).thenCompose(v -> bakePizza(pizza, orderId)));
    }

    private CompletableFuture<Pizza> prepareDough(Pizza pizza, Integer orderId) {
        return CompletableFuture.supplyAsync(() -> {

            if(getTaskState() == TaskState.DISPLACED) {

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

                    if (getTaskState() == TaskState.DISPLACED) {
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

            if(getTaskState() == TaskState.DISPLACED) {
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

                    if (getTaskState() == TaskState.DISPLACED) {
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

            pizzaStage = PizzaStage.DONE;
            setTaskState(TaskState.COMPLETED);
            System.out.printf("%s%s✨ [BAKE_DONE] Заказ #%d | Пицца '%s' id{%d} | Готово!%s%n",
                    GREEN, time, orderId, pizza.getName(), pizza.getId(), RESET);

            getResultFuture().complete(pizza); // событие готовности

            return pizza;
        }, getAssignedPool());
    }
}