package org.example.task;

import lombok.Getter;
import lombok.Setter;
import org.example.KitchenService;
import org.example.dish.Pizza;
import org.example.dish.PizzaStage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Задача приготовления пиццы с поддержкой повторных попыток (retries) и вытеснения VIP-заказами.
 *
 * <p><b>Жизненный цикл:</b>
 * <pre>
 * {@link PizzaStage#NONE} → {@link PizzaStage#DOUGH} → {@link PizzaStage#BAKING} → {@link PizzaStage#DONE}
 *                                  ↓                        ↓
 *                          (при прерывании)         (при подгорании)
 *                                  ↓                        ↓
 *                          сохраняем DOUGH           {@link PizzaStage#FIRED}
 *                                  ↓
 *                           в recoveryQueue         повторная попытка
 *                                                   (пока есть {@link #remainingRetries})
 * </pre>
 *
 * <p><b>Вероятность подгорания:</b> {@value #PIZZA_FAIL_PERCENT} (30%)
 *
 * <p><b>Механизм вытеснения:</b> При занятости всех печей VIP-заказ может прервать
 * обычную задачу. Прерванная задача переводится в состояние {@link TaskState#DISPLACED},
 * сохраняет текущий этап приготовления в {@link #pizzaStage} и помещается в
 * {@link KitchenService#recoveryQueue} для последующего восстановления.
 *
 * @author Egrius
 * @see KitchenService
 * @see PizzaStage
 * @see CookingTask
 */
@Getter
@Setter
public class PizzaTask extends CookingTask<Pizza> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";

    /**
     * Оставшееся количество попыток приготовления.
     */
    private final AtomicInteger remainingRetries;

    /**
     * Вероятность того, что пицца подгорит при выпечке.
     * <p>Значение: {@value #PIZZA_FAIL_PERCENT} (30%)
     */
    private final double PIZZA_FAIL_PERCENT = 0.3;

    /**
     * Текущий этап приготовления пиццы.
     * <p>Используется для восстановления после вытеснения VIP-заказом.
     */
    private volatile PizzaStage pizzaStage = PizzaStage.NONE;

    /**
     * Конструктор задачи приготовления пиццы.
     *
     * @param dish          объект пиццы для приготовления
     * @param orderId       идентификатор заказа
     * @param assignedPool  пул потоков для выполнения (печь)
     * @param isVip         флаг VIP-заказа (может вытеснять обычные)
     * @param retries       максимальное количество повторных попыток при подгорании
     */
    public PizzaTask(Pizza dish, int orderId, ExecutorService assignedPool, boolean isVip, int retries) {
        super(dish, orderId, assignedPool, isVip);

        remainingRetries = new AtomicInteger(retries);
    }


    /**
     * Запускает процесс приготовления пиццы.
     *
     * <p><b>Поток выполнения:</b>
     * <ol>
     *   <li>Проверяет, не была ли задача вытеснена до старта</li>
     *   <li>Запускает асинхронную цепочку: подготовка теста → выпечка с ретраями</li>
     *   <li>Сохраняет {@code cookingFuture} для управления через {@code KitchenService}</li>
     *   <li>Сигнализирует через {@code startLatch} о начале выполнения</li>
     * </ol>
     *
     * @return {@code CompletableFuture}, который завершится:
     *         <ul>
     *           <li>успешно — с готовой {@link Pizza}</li>
     *           <li>с ошибкой — после исчерпания всех попыток</li>
     *         </ul>
     */
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


        return getResultFuture(); // Пока временно, вообще смысла не имеет возвращать что-то
    }

    /**
     * Восстанавливает выполнение прерванной задачи.
     *
     * <p>В зависимости от сохранённого {@link #pizzaStage}:
     * <ul>
     *   <li>{@code NONE} — запуск с начала</li>
     *   <li>{@code DOUGH} — повторная подготовка теста (безопасно, т.к. тесто не начато)</li>
     *   <li>{@code BAKING} — продолжение выпечки (с того же места)</li>
     *   <li>{@code DONE}/{@code FAILED} — завершённые задачи не восстанавливаются</li>
     * </ul>
     *
     * @return {@code CompletableFuture} восстановленного процесса приготовления
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
    Суть: установить флаг отмены для кооперативного вытеснения.

    !!! Future.cancel() не подходит под данную задачу.
     */

    /**
     * Прерывает выполнение задачи для освобождения ресурсов под VIP-заказ.
     *
     * <p><b>Важно:</b> Используется {@link TaskState#DISPLACED} вместо
     * {@code Future.cancel()}, так как последний не позволяет сохранить
     * промежуточное состояние ({@link #pizzaStage}) для последующего восстановления.
     *
     * <p>Задача не прерывается принудительно, а лишь устанавливает флаг,
     * который проверяется в точках кооперативной многозадачности
     * (методы {@link #prepareDough} и {@link #bakePizza}).
     */
    public void cancel() {

        // Если пицца уже готова, то ничего не менять
        if (pizzaStage == PizzaStage.DONE) {
            return;
        }

       setTaskState(TaskState.DISPLACED);

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s⚠️ [PIZZA_CANCEL] Заказ #%d | Пицца '%s' id{%d} | Статус: %s%s%n",
                YELLOW, time, getOrderId(), getDish().getName(), getDish().getId(), getPizzaStage(), RESET);
    }

    /**
     * Обрабатывает подгорание пиццы и управляет повторными попытками.
     *
     * <p><b>Алгоритм работы:</b>
     * <ol>
     *   <li>При получении ошибки проверяет, связана ли она с подгоранием</li>
     *   <li>Уменьшает счётчик {@link #remainingRetries}</li>
     *   <li>Если попытки есть — рекурсивно запускает выпечку заново</li>
     *   <li>Если попыток не осталось — переводит задачу в состояние {@code FAILED}</li>
     * </ol>
     *
     * @param futureToComplete метод этапа приготовления (ожидается, что может выбросить
     *                         {@code RuntimeException} с сообщением, содержащим "подгорела")
     * @return {@code CompletableFuture} с конечным статусом приготовления пиццы
     */
    private CompletableFuture<Pizza> runFutureTaskAndReturnPizzaResult(CompletableFuture<Pizza> futureToComplete) {
        return futureToComplete
                .exceptionallyCompose((throwable) -> {

                    // Если задача уже провалена, то вернуть конечный статус
                    if (getTaskState() == TaskState.FAILED) {
                        return CompletableFuture.failedFuture(throwable);
                    }

                    Pizza pizza = getDish();
                    String time = LocalDateTime.now().format(TIME_FORMATTER);

                    // Если ошибка не представляет собой подгорание пиццы, то вернуть завершённый данной ошибкой CompletableFuture
                    if (throwable.getMessage() != null && !throwable.getMessage().contains("подгорела")) {

                        // Завершение главного фьючера, по которому ожидается задача
                        getResultFuture().completeExceptionally(throwable);
                        // Возврат завершенного данной ошибкой CompletableFuture
                        return CompletableFuture.failedFuture(throwable);
                    }

                    // Получение оставшихся попыток (вычитаем, т.к. попадание сюда уже и есть следующая попытка)
                    int newRetries = remainingRetries.decrementAndGet();

                    System.out.printf("%s%s💀 [BURNT] Заказ #%d | Пицца '%s' id{%d} подгорела | retry: %d осталось%s%n",
                            RED, time, orderId, pizza.getName(), pizza.getId(), newRetries, RESET);
                    setPizzaStage(PizzaStage.FIRED);

                    // Если не осталось попыток
                    if (newRetries  <= 0) {

                        setTaskState(TaskState.FAILED);
                        pizzaStage = PizzaStage.FAILED;

                        System.out.printf("%s%s❌ [PIZZA_FAIL] Заказ #%d | Пиццу '%s' id{%d} не удалось приготовить (ретри закончились)%s%n",
                                RED, time, orderId, pizza.getName(), pizza.getId(), RESET);

                        RuntimeException error = new RuntimeException(
                                "[заказ %d]: 💥 Пиццу '%s' с id{%d} не удалось приготовить"
                                        .formatted(orderId, pizza.getName(), pizza.getId()));

                        // Завершение главного фьючера, по которому ожидается задача
                        getResultFuture().completeExceptionally(error);

                        // Возврат CompletableFuture, завершённого неудачно
                        return CompletableFuture.failedFuture(error);
                    }

                    System.out.printf("%s%s🔄 [PIZZA_RETRY] Заказ #%d | Пицца '%s' id{%d} | Повторная попытка (%d осталось)%s%n",
                            YELLOW, time, orderId, pizza.getName(), pizza.getId(), newRetries, RESET);

                    // Если остались попытки, то рекурсивно вызвать приготовление ещё раз и обработать рекурсивный результат.
                    return runFutureTaskAndReturnPizzaResult(bakePizza(pizza, orderId));
                });
    }

    /**
     * Запускает полный цикл приготовления пиццы с поддержкой повторных попыток.
     *
     * @param pizza   пицца для приготовления
     * @param orderId идентификатор заказа
     * @return {@code CompletableFuture} с результатом приготовления
     * @see #prepareDough(Pizza, Integer)
     * @see #bakePizza(Pizza, Integer)
     */
    private CompletableFuture<Pizza> makePizzaWithRetries(Pizza pizza, Integer orderId) {
        return runFutureTaskAndReturnPizzaResult(
                prepareDough(pizza, orderId).thenCompose(v -> bakePizza(pizza, orderId)));
    }

    /**
     * Выполняет этап замеса теста.
     *
     * <p>Имитирует работу с задержкой 2×100 мс. В процессе выполнения
     * периодически проверяет флаг прерывания {@link TaskState#DISPLACED}.
     *
     * @param pizza   пицца для приготовления
     * @param orderId идентификатор заказа
     * @return {@code CompletableFuture} с пиццей после завершения замеса теста
     */
    private CompletableFuture<Pizza> prepareDough(Pizza pizza, Integer orderId) {
        return CompletableFuture.supplyAsync(() -> {

            /*
            Если задачу прервали, то вернуть пиццу с её текущим статусом и не продолжать дальше
            */
            if(getTaskState() == TaskState.DISPLACED) {
                System.out.println("ПРЕРВАНО ПЕРЕД ЗАМЕШИВАНИЕМ ТЕСТА");
                return pizza;
            }

            // Установили статус замешивания теста
            pizzaStage = PizzaStage.DOUGH;

            String time = LocalDateTime.now().format(TIME_FORMATTER);
            System.out.printf("%s%s🥣 [DOUGH_START] Заказ #%d | Пицца '%s' id{%d} | Замес теста...%s%n",
                    BLUE, time, orderId, pizza.getName(), pizza.getId(), RESET);

            try {
                // Имитация замеса теста.
                for (int i = 0; i < 2; i++) {
                    Thread.sleep(100);

                    // Проверка статуса на прерывание (задача была смещена VIP-задачей)
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

    /**
     * Выполняет этап выпекания пиццы.
     *
     * <p>Имитирует работу с задержкой 2×200 мс. С вероятностью
     * {@value #PIZZA_FAIL_PERCENT} генерирует ошибку подгорания.
     *
     * <p><b>Важно:</b> При подгорании вызывает {@code getResultFuture().completeExceptionally()}
     * для немедленного оповещения ожидающих потоков (например, {@code KitchenService.acceptOrder()}).
     *
     * @param pizza   пицца для приготовления
     * @param orderId идентификатор заказа
     * @return {@code CompletableFuture} с приготовленной пиццей или ошибкой подгорания
     */
    private CompletableFuture<Pizza> bakePizza(Pizza pizza, Integer orderId) {

        return CompletableFuture.supplyAsync(() -> {

            /*
            Если задачу прервали, то вернуть пиццу с её текущим статусом и не продолжать дальше
            */
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

                    RuntimeException error = new RuntimeException(
                            "[заказ %d]: ❌ Пицца '%s', id{%d} подгорела"
                                    .formatted(orderId, pizza.getName(), pizza.getId()));

                    // !!! Была проблема, что даже после того как заказ был уже провален, отсюда всё равно кидалось исключение.
                    // !!! Так понял что потому что не завершал результирующий фьючер
                    getResultFuture().completeExceptionally(error);
                    throw error;
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

    /**
     * {@inheritDoc}
     */
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
}