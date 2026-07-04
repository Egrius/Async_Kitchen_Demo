package org.example;

import org.example.dish.*;
import org.example.task.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

// VIP-заказы должны прерывать уже текущие обычные и забирать их пул, затем пул продолжает выполнять прошлую задачу.
// Нужно продумать отмену задач и также их возобновление при отмене.

// Вип прервал выполняющуюся -> исходная задача должна будет возобновиться после того как вип освободит место, или же место уже будет свободно,
// Т.е нужно сохранить отмененную задачу и повторить её, тем самым она встанет в очередь в пул и будет конкурировать с остальными
// Если же я хочу в таком же порядке, чтобы она сто проц по освобождению от випов шла заново, то нужно делать приоритетную очередь отмененных задач,
// которая будет выполняться шедулером, либо же если фьючер позволяет, то его можно перезапустить? Но тут именно нужно сам заказ брать, если у него
// уже часть выполнена то доделать эту часть

/**
 * <p>Сервис, моделирующий работу кухни ресторана с асинхронным приготовлением блюд.</p>
 *
 * <p><b>Основные возможности:</b>
 * <ul>
 *   <li>Приём заказов и генерация задач для каждого блюда</li>
 *   <li>Приоритезация VIP-заказов с вытеснением обычных (preemptive scheduling)</li>
 *   <li>Пул печей ограниченного размера ({@value #OVEN_POOL_SIZE}) для приготовления пиццы</li>
 *   <li>Отдельные пулы для напитков и десертов (по одному потоку)</li>
 *   <li>Восстановление прерванных задач через очередь восстановления</li>
 *   <li>Graceful shutdown с ожиданием завершения задач</li>
 * </ul>
 *
 * <p><b>Архитектура:</b>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                     KitchenService                          │
 * │  (Singleton, управляет всеми задачами и пулами потоков)     │
 * └───────────────┬───────────────────┬─────────────────┬───────┘
 *                 │                   │                 │
 *     ┌───────────▼──────────┐ ┌──────▼───────┐ ┌───────▼────────┐
 *     │    Oven Pool (2)     │ │ Drink Pool   │ │  Dessert Pool  │
 *     │   для задач Pizza    │ │   (1 поток)  │ │    (1 поток)   │
 *     └───────────┬──────────┘ └──────────────┘ └────────────────┘
 *                 │
 *     ┌───────────▼──────────┐
 *     │   runningPizzaTasks  │ <-- текущие задачи в печах
 *     │   recoveryQueue      │ <-- прерванные задачи (для восстановления)
 *     └──────────────────────┘
 * </pre>
 *
 * <p><b>Жизненный цикл задачи:</b>
 * <ol>
 *   <li>Задача создаётся в {@link #generateCookingTasks} и помещается в {@link #tasksByOrderId}</li>
 *   <li>Dispatcher (каждые 2 секунды) пытается запустить задачи через {@link #tryStartCookingTasks}</li>
 *   <li>Для пицц проверяется наличие свободной печи:
 *     <ul>
 *       <li>Если печь свободна -> запуск</li>
 *       <li>Если печь занята и задача VIP -> попытка вытеснить обычную задачу</li>
 *       <li>Если печь занята и задача обычная -> отложенный старт</li>
 *     </ul>
 *   </li>
 *   <li>При вытеснии задача помещается в {@link #recoveryQueue}</li>
 *   <li>При освобождении печи задачи из очереди восстановления запускаются через {@link #tryRecoverFromRecoveryQueue}</li>
 * </ol>
 *
 * <p><b>Потокобезопасность:</b>
 * Класс является потокобезопасным:
 * <ul>
 *   <li>{@link #tasksByOrderId} — {@link ConcurrentHashMap}</li>
 *   <li>{@link #runningPizzaTasks} — потокобезопасный набор</li>
 *   <li>{@link #recoveryQueue} — {@link PriorityBlockingQueue}</li>
 *   <li>Все пулы потоков ({@code ExecutorService}) потокобезопасны</li>
 * </ul>
 *
 * @author Egrius
 * @see PizzaTask
 * @see CookingTask
 * @see OrderService
 */
public class KitchenService {

    private static volatile KitchenService INSTANCE = null;

    /**
     * Максимальное количество повторных попыток для пиццы при подгорании.
     */
    private final int PIZZA_RETRIES = 2;

    /**
     * Размер пула печей (одновременно может готовиться только 2 пиццы).
     */
    private static final int OVEN_POOL_SIZE = 2;

    /**
     * Статусы задач, которые не должны запускаться повторно.
     */
    private static final Set<TaskState> notToStartTaskStatuses = Set.of(TaskState.COMPLETED, TaskState.FAILED, TaskState.RUNNING);

    /**
     * Хранилище всех задач по заказам.
     * <p>Ключ: ID заказа</p>
     * <p>Значение: список задач приготовления блюд заказа</p>
     * <p>Используется для отслеживания всех задач и их последующего восстановления.</p>
     */
    private static Map<Integer, List<CookingTask<? extends Dish>>> tasksByOrderId = new ConcurrentHashMap<>();

    /**
     * Набор текущих выполняющихся задач пиццы (занятые печи).
     * <p>Размер набора не может превышать {@link #OVEN_POOL_SIZE}</p>
     */
    private static final Set<PizzaTask> runningPizzaTasks = ConcurrentHashMap.newKeySet(OVEN_POOL_SIZE);

    /**
     * Очередь прерванных (вытесненных VIP) задач пиццы.
     * <p>Задачи хранятся с приоритетом по ID заказа.</p>
     * <p>Восстанавливаются при освобождении печи.</p>
     */
    private final PriorityBlockingQueue<PizzaTask> recoveryQueue = new PriorityBlockingQueue<>(100,
            Comparator.comparing(CookingTask::getOrderId));

    /**
     * Пул потоков для выпекания пиццы (печи).
     * Размер: {@value #OVEN_POOL_SIZE}
     */
    private static final ExecutorService ovenPool = Executors.newFixedThreadPool(OVEN_POOL_SIZE);

    /**
     * Пул потоков для приготовления напитков (один поток).
     */
    private static final ExecutorService drinkPool = Executors.newSingleThreadExecutor();

    /**
     * Пул потоков для приготовления десертов (один поток).
     */
    private static final ExecutorService dessertPool = Executors.newSingleThreadExecutor();

    /**
     * Диспетчер для периодического запуска задач.
     * <p>Выполняется каждые 2 секунды.</p>
     */
    private static final ScheduledExecutorService dispatcher = new ScheduledThreadPoolExecutor(1);

    /**
     * Форматтер для времени в логах.
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String PURPLE = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";

    /**
     * Возвращает единственный экземпляр сервиса (потокобезопасный синглтон с lazy initialization).
     *
     * @return экземпляр KitchenService
     */
    public static KitchenService getInstance() {
        if(INSTANCE == null) {
            synchronized (KitchenService.class) {
                INSTANCE = new KitchenService();
            }
        }
        return INSTANCE;
    }

    /**
     * Приватный конструктор.
     * <p>Запускает диспетчер для периодического выполнения задач.</p>
     */
    private KitchenService() {
        dispatcher.scheduleWithFixedDelay(runCookingTasks(), 2, 2, TimeUnit.SECONDS);
    }

    /**
     * Создаёт задачу диспетчера для периодического выполнения.
     *
     * @return {@code Runnable}, выполняющий:
     *         <ol>
     *           <li>Очистку неактивных задач ({@link #removeInactiveTasks})</li>
     *           <li>Восстановление из очереди ({@link #tryRecoverFromRecoveryQueue})</li>
     *           <li>Запуск новых задач ({@link #tryStartCookingTasks})</li>
     *         </ol>
     */
    private Runnable runCookingTasks() {
        return () -> {
            removeInactiveTasks();
            tryRecoverFromRecoveryQueue();
            tryStartCookingTasks();
        };
    }

    /**
     * Принимает заказ на кухню и инициирует его приготовление.
     *
     * <p><b>Процесс обработки заказа:</b>
     * <ol>
     *   <li>Создаются задачи для каждого блюда через {@link #generateCookingTasks}</li>
     *   <li>Метод ожидает создания всех задач через {@link CountDownLatch}</li>
     *   <li>Затем ожидает завершения всех задач через {@link CompletableFuture#join()}</li>
     *   <li>Собирает готовые блюда и возвращает заказ</li>
     * </ol>
     *
     * <p><b>Важно:</b> Метод блокирует вызывающий поток до завершения всех задач заказа.
     *
     * @param order заказ для выполнения
     * @return {@code CompletableFuture}, который завершится:
     *         <ul>
     *           <li>успешно — с готовым {@link Order}</li>
     *           <li>с ошибкой — если любое из блюд не удалось приготовить</li>
     *         </ul>
     * @throws InterruptedException если ожидание создания задач было прервано
     */
    public CompletableFuture<Order> acceptOrder(Order order) throws InterruptedException {

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String vipStatus = order.isVip() ? "VIP: да" : "VIP: нет";
        System.out.printf("%s%s🍳 [ORDER_ACCEPTED] Заказ #%d | %s | блюд: %d%s%n",
                CYAN, time, order.getId(), vipStatus, order.getDishesOrdered().size(), RESET);

        System.out.println("кол-во блюд в заказе: " + order.getDishesOrdered().size());

        generateCookingTasks(order);

        System.out.printf("%s%s🚀 [TASKS_STARTED] Заказ #%d | Задачи были созданы и запущены...%s%n",
                GREEN, time, order.getId(), RESET);

        List<? extends CompletableFuture<? extends Dish>> resultFutures = tasksByOrderId.get(order.getId())
                .stream()
                .map(CookingTask::getResultFuture)
                .toList();

        List<Dish> dishesGot = new ArrayList<>();

        try {
            for(CompletableFuture<? extends Dish> f : resultFutures) {
                // Для обёртки unchecked исключений
                dishesGot.add(f.join());
            }

            order.setDishedGot(dishesGot);
            return CompletableFuture.completedFuture(order);

        } catch (CompletionException e) {
            return CompletableFuture.failedFuture(e);

        } finally {
            tasksByOrderId.remove(order.getId());
        }
    }

    /**
     * Генерирует задачи для каждого блюда в заказе.
     * <p>Тип задачи определяется типом блюда:
     * <ul>
     *   <li>{@code PIZZA} -> {@link PizzaTask} (использует пул печей)</li>
     *   <li>{@code DRINK} -> {@link DrinkTask} (использует пул напитков)</li>
     *   <li>{@code DESSERT} -> {@link DessertTask} (использует пул десертов)</li>
     * </ul>
     *
     * @param order заказ, для которого генерируются задачи
     */
    private void generateCookingTasks(Order order) {
        int orderId = order.getId();
        boolean isVip = order.isVip();

        String time = LocalDateTime.now().format(TIME_FORMATTER);

        for(Dish dish : order.getDishesOrdered()) {
            switch (dish.getType()) {
                case PIZZA -> {
                    System.out.printf("%s%s➕ [TASK_CREATED] Заказ #%d | Тип: PIZZA | Блюдо: '%s'%s%n",
                            CYAN, time, orderId, dish.getName(), RESET);

                    PizzaTask task = new PizzaTask((Pizza)dish, orderId, ovenPool, isVip, PIZZA_RETRIES);
                    tasksByOrderId.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
                case DRINK -> {
                    System.out.printf("%s%s➕ [TASK_CREATED] Заказ #%d | Тип: DRINK | Блюдо: '%s'%s%n",
                            CYAN, time, orderId, dish.getName(), RESET);

                    DrinkTask task = new DrinkTask((Drink) dish, orderId, drinkPool, isVip);
                    tasksByOrderId.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
                case DESSERT -> {
                    System.out.printf("%s%s➕ [TASK_CREATED] Заказ #%d | Тип: DESSERT | Блюдо: '%s'%s%n",
                            CYAN, time, orderId, dish.getName(), RESET);

                    DessertTask task = new DessertTask((Dessert) dish, orderId, dessertPool, isVip);
                    tasksByOrderId.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
            }
        }
    }

    /**
     * Проверяет наличие свободного места в печи.
     *
     * @return {@code true}, если количество выполняющихся задач пиццы меньше размера пула
     */
    private boolean hasFreeOvenSlot() {
        return runningPizzaTasks.size() < OVEN_POOL_SIZE;
    }

    /**
     * Запускает задачи, готовые к выполнению.
     * <p>Проходит по всем заказам и их задачам. Для каждой задачи в статусе,
     * отличном от {@code COMPLETED}, {@code FAILED}, {@code RUNNING}:</p>
     * <ul>
     *   <li>Для напитков и десертов — немедленный запуск</li>
     *   <li>Для пиццы:
     *     <ul>
     *       <li>Если есть свободная печь — запуск</li>
     *       <li>Если печь занята и задача VIP — попытка вытеснить обычную задачу</li>
     *       <li>Иначе — отложенный старт (логирование)</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    private void tryStartCookingTasks() {
        for(Map.Entry<Integer, List<CookingTask<? extends Dish>>> e : tasksByOrderId.entrySet()) {
            for (CookingTask<?> task : e.getValue()) {
                // Если не эта проверка, то он и completed по новой запускает и failed
                if (!notToStartTaskStatuses.contains(task.getTaskState())) {
                    if(task instanceof PizzaTask) {

                        if(!hasFreeOvenSlot()) {
                            if(task.isVip()) {
                                String time = LocalDateTime.now().format(TIME_FORMATTER);

                                if(findRunningTaskAndCancel()) {
                                    System.out.printf("%s%s⚡ [VIP_PREEMPT] VIP заказ #%d прервал задачу заказа #%d (Пицца '%s')%s%n",
                                            YELLOW, time, task.getOrderId(), task.getOrderId(), task.getDish().getName(), RESET);

                                    task.start();
                                    runningPizzaTasks.add((PizzaTask) task);

                                } else {
                                    System.out.printf("%s%s🚫 [VIP_DISPLACEMENT_FAIL] VIP заказ #%d не смог прервать задачу (Пицца '%s')%s%n",
                                            YELLOW, time, task.getOrderId(), task.getDish().getName(), RESET);
                                }
                            } else {
                                String time = LocalDateTime.now().format(TIME_FORMATTER);
                                System.out.printf("%s%s🕒 [PIZZA_START_DELAYED] заказ #%d не может начать готовиться, пока занята печь (Пицца '%s')%s%n",
                                        YELLOW, time, task.getOrderId(), task.getDish().getName(), RESET);
                            }
                        } else {
                            task.start();
                            runningPizzaTasks.add((PizzaTask) task);
                        }
                    } else {
                        task.start();
                    }
                }
            }
        }
    }

    /**
     * Удаляет из набора {@link #runningPizzaTasks} задачи, которые больше не выполняются.
     * <p>Вызывается перед каждым циклом диспетчера.</p>
     */
    private void removeInactiveTasks() {
        runningPizzaTasks.removeIf(task -> task.getTaskState() != TaskState.RUNNING);
    }

    /**
     * Восстанавливает задачи из очереди прерванных.
     * <p>Пытается извлечь задачу из {@link #recoveryQueue} и запустить её,
     * если есть свободная печь и задача не выполняется.</p>
     * <p>Если печь занята, задача возвращается обратно в очередь.</p>
     */
    private void tryRecoverFromRecoveryQueue() {
        String time = LocalDateTime.now().format(TIME_FORMATTER);

        System.out.printf("%s%s🔄 [DISPATCHER_TICK] Проверка очередей...%s%n", PURPLE, time, RESET);
        System.out.println("RECOVERY QUEUE SIZE: " + recoveryQueue.size());

        // Восстановление отменённых задач
        PizzaTask cancelledTask = recoveryQueue.poll();

        while (cancelledTask != null) {
            System.out.println("Вытянуто из очереди: " + cancelledTask);
            boolean freeSlot = hasFreeOvenSlot();
            System.out.println("hasFreeOvenSlot(): " + freeSlot);

            if (hasFreeOvenSlot() && cancelledTask.getTaskState() != TaskState.RUNNING) {

                System.out.printf("%s%s♻️ [RECOVERY] Восстановление задачи | Заказ #%d | Тип: %s%s%n",
                        PURPLE, time, cancelledTask.getOrderId(),
                        cancelledTask.getDish().getType(), RESET);

                runningPizzaTasks.add(cancelledTask);
                cancelledTask.resume();
            } else {
                recoveryQueue.offer(cancelledTask);
                break;
            }
            cancelledTask = recoveryQueue.poll();
        }
    }

    /**
     * Находит обычную (non-VIP) выполняющуюся задачу пиццы и отменяет её.
     * <p>Используется для вытеснения обычной задачи VIP-заказом.</p>
     * <p>Отменённая задача помещается в {@link #recoveryQueue}.</p>
     *
     * @return {@code true}, если задача была найдена и отменена, иначе {@code false}
     */
    private boolean findRunningTaskAndCancel() {
        Iterator<PizzaTask> runningPizzaTaskIterator = runningPizzaTasks.iterator();

        while(runningPizzaTaskIterator.hasNext()) {
            PizzaTask t = runningPizzaTaskIterator.next();

            if(!t.isVip()) {
                runningPizzaTaskIterator.remove();
                t.cancel();
                recoveryQueue.add(t);

                String time = LocalDateTime.now().format(TIME_FORMATTER);
                System.out.printf("%s%s❌ [CANCEL] Отмена задачи | Заказ #%d | Причина: вытеснение VIP%s%n",
                        RED, time, t.getOrderId(), RESET);

                return true;
            }
        }
        return false;
    }

    /**
     * Корректно завершает работу кухни.
     * <p>Последовательно отключает все пулы потоков, ожидая завершения текущих задач
     * в течение 10 секунд. При превышении таймаута — принудительная остановка.</p>
     *
     * <p><b>Порядок завершения:</b>
     * <ol>
     *   <li>Пул печей (ovenPool)</li>
     *   <li>Пул напитков (drinkPool)</li>
     *   <li>Пул десертов (dessertPool)</li>
     *   <li>Диспетчер (dispatcher)</li>
     * </ol>
     */
    public void shutdown() {
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s🔒 [SHUTDOWN] Кухня закрывается...%s%n", RED, time, RESET);

        ovenPool.shutdown();
        drinkPool.shutdown();
        dessertPool.shutdown();
        dispatcher.shutdown();

        try {
            if(!ovenPool.awaitTermination(10, TimeUnit.SECONDS)) ovenPool.shutdownNow();
            if (!drinkPool.awaitTermination(10, TimeUnit.SECONDS)) drinkPool.shutdownNow();
            if (!dessertPool.awaitTermination(10, TimeUnit.SECONDS)) dessertPool.shutdownNow();
            if (!dispatcher.awaitTermination(10, TimeUnit.SECONDS)) dispatcher.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        System.out.printf("%s%s✅ [SHUTDOWN] Кухня закрыта%s%n", GREEN, time, RESET);
    }

}
