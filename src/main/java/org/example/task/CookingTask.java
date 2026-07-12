package org.example.task;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.example.Order;
import org.example.dish.Dish;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * <p>Абстрактный базовый класс для задачи приготовления одного блюда на кухне.</p>
 *
 * <p>Каждое блюдо заказа порождает экземпляр {@code CookingTask}, который инкапсулирует:
 * <ul>
 *   <li>само блюдо {@link #dish}</li>
 *   <li>идентификатор заказа {@link #orderId}</li>
 *   <li>приоритет (VIP/обычный) {@link #isVip}</li>
 *   <li>пул потоков для выполнения {@link #assignedPool}</li>
 *   <li>состояние задачи {@link #taskState}</li>
 *   <li>результирующий {@link CompletableFuture} {@link #resultFuture}, который будет завершён по готовности блюда.
 *      По этому фьючеру идёт ожидание внутри сервиса кухни {@link org.example.KitchenService#acceptOrder(Order)}</li>
 *   <li>{@link CountDownLatch} для синхронизации старта всех задач заказа</li>
 * </ul>
 * </p>
 *
 * <p><b>Жизненный цикл задачи:</b>
 * <ol>
 *   <li>Создание объекта в статусе {@link TaskState#CREATED}</li>
 *   <li>Вызов {@link #start()} -> задача переходит в статус {@link TaskState#RUNNING} (или позже при реальном запуске)</li>
 *   <li>По окончании приготовления {@link #resultFuture} завершается успешно или с ошибкой</li>
 *   <li>Задача может быть прервана (VIP-вытеснение) — статус {@link TaskState#DISPLACED}</li>
 *   <li>При возобновлении (только для пиццы) статус возвращается в {@link TaskState#RUNNING}</li>
 *   <li>Терминальные статусы: {@link TaskState#COMPLETED}, {@link TaskState#FAILED}</li>
 * </ol>
 * </p>
 *
 * <p><b>Потокобезопасность:</b>
 * <ul>
 *   <li>Поля {@code orderId}, {@code isVip}, {@code dish}, {@code assignedPool}, {@code startLatch} — неизменяемы (final) и потокобезопасны.</li>
 *   <li>{@code taskState} — {@code volatile}, изменения видны всем потокам.</li>
 *   <li>{@code cookingFuture} и {@code resultFuture} — операции над ними потокобезопасны.</li>
 * </ul>
 * </p>
 *
 * @param <T> тип блюда (наследник {@link Dish})
 * @author Egrius
 * @see PizzaTask
 * @see DrinkTask
 * @see DessertTask
 */
@Getter
@Setter
@ToString(exclude = {"cookingFuture", "assignedPool"})
public abstract class CookingTask <T extends Dish> {
    /**
     * Идентификатор заказа, к которому относится блюдо.
     */
    protected final int orderId;

    /**
     * Флаг VIP-статуса заказа.
     * <p>Влияет на приоритет при вытеснении задач (только для пиццы).</p>
     */
    protected final boolean isVip;

    /**
     * Блюдо, которое нужно приготовить.
     */
    protected final T dish;

    /**
     * Пул потоков, в котором выполняется задача (зависит от типа блюда).
     * <p>Для пиццы — {@link org.example.KitchenService#ovenPool} (размер 2),<br>
     * для напитков — {@link org.example.KitchenService#drinkPool} (один поток),<br>
     * для десертов — {@link org.example.KitchenService#dessertPool} (один поток).</p>
     */
    private final ExecutorService assignedPool;

    /**
     * Future, представляющий сам процесс приготовления (асинхронная операция).
     * <p>Используется для управления задачей (например, отмена, ожидание).</p>
     */
    private volatile CompletableFuture<T> cookingFuture;

    /**
     * Future, который будет завершён результатом приготовления блюда.
     * <p>Этот future ожидается в {@link org.example.KitchenService#acceptOrder} для сбора готовых блюд.</p>
     */
    private CompletableFuture<T> resultFuture;

    /**
     * Счётчик для синхронизации запуска всех задач одного заказа.
     * <p>Каждая задача вызывает {@link #signalStarted()} при старте, уменьшая счётчик.
     * Основной поток в {@link org.example.KitchenService#acceptOrder} ожидает {@code startLatch.await()}.</p>
     */
//    private final CountDownLatch startLatch;

    /**
     * Текущее состояние задачи.
     * <p>Изменяется в процессе жизни задачи (создана, выполняется, вытеснена, завершена, провалена).</p>
     */
    private volatile TaskState taskState;

    /**
     * Конструктор задачи приготовления блюда.
     *
     * @param dish         блюдо для приготовления
     * @param orderId      идентификатор заказа
     * @param assignedPool пул потоков для выполнения
     * @param isVip        флаг VIP-заказа
     */
    public CookingTask(T dish, int orderId, ExecutorService assignedPool, boolean isVip/*, CountDownLatch startLatch */) {
        this.dish = dish;
        this.orderId = orderId;
        this.assignedPool = assignedPool;
        this.isVip = isVip;
       // this.startLatch = startLatch;
        this.taskState = TaskState.CREATED;
        this.resultFuture = new CompletableFuture<>();
    }

    /**
     * Запускает процесс приготовления блюда.
     * <p>Реализация должна:
     * <ul>
     *   <li>создать асинхронную цепочку (через {@code CompletableFuture.supplyAsync} с использованием {@code assignedPool})</li>
     *   <li>сохранить полученный future в {@link #cookingFuture}</li>
     *   <li>вернуть {@link #resultFuture}, который будет завершён по окончании приготовления</li>
     * </ul>
     * </p>
     *
     * @return {@code CompletableFuture}, который завершится готовым блюдом или ошибкой
     */
    public abstract CompletableFuture<T> start();

}