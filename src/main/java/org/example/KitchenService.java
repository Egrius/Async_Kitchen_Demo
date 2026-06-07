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
// которая будет выполнятся шедулером, либо же если фьючер позволяет, то его можно перезапустить? Но тут именно нужно сам заказ брать, если у него
// уже часть выполнена то доделать эту часть
public class KitchenService {

    private static volatile KitchenService INSTANCE = null;

    private final int PIZZA_RETRIES = 2;
    private static final int OVEN_POOL_SIZE = 2;

    private static final Set<TaskState> notToStartTaskStatuses = Set.of(TaskState.COMPLETED, TaskState.FAILED, TaskState.RUNNING);

    // Здесь хранятся все выполняющиеся задачи, благодаря чему можно прерывать выполняющиеся и всунуть випа
    private static Map<Integer, List<CookingTask<? extends Dish>>> tasksByOrderId = new ConcurrentHashMap<>();

    private static final Set<PizzaTask> runningPizzaTasks = ConcurrentHashMap.newKeySet(OVEN_POOL_SIZE);

    private final PriorityBlockingQueue<PizzaTask> recoveryQueue = new PriorityBlockingQueue<>(100,
            Comparator.comparing(CookingTask::getOrderId));

    private static final ExecutorService ovenPool = Executors.newFixedThreadPool(OVEN_POOL_SIZE);
    private static final ExecutorService drinkPool = Executors.newSingleThreadExecutor();
    private static final ExecutorService dessertPool = Executors.newSingleThreadExecutor();

    private static final ScheduledExecutorService dispatcher = new ScheduledThreadPoolExecutor(1);

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String PURPLE = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";

    public static KitchenService getInstance() {
        if(INSTANCE == null) {
            synchronized (KitchenService.class) {
                INSTANCE = new KitchenService();
            }
        }
        return INSTANCE;
    }

    private KitchenService() {
        dispatcher.scheduleWithFixedDelay(runCookingTasks(), 2, 2, TimeUnit.SECONDS);
    }

    public static void unregisterPizza(PizzaTask task) {
        runningPizzaTasks.remove(task);
    }

    /*
    Проблема: связка шедуллера на старт фьючеров и allOf в acceptOrder - нет синхронизации вследствие чего null
     */
    private Runnable runCookingTasks() {
        return () -> {
            removeInactiveTasks();
            tryRecoverFromRecoveryQueue();
            tryStartCookingTasks();
        };
    }


    // Суть этого метода - принять заказ на кухню, сгенерировать задачи для этого заказа
    // и пометсить их в мапу (вызвать generateCookingTasks()).
    // После чего текущий поток будет ожидать завершения готовки всех своих заказов
    public CompletableFuture<Order> acceptOrder(Order order) throws InterruptedException {

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String vipStatus = order.isVip() ? "VIP: да" : "VIP: нет";
        System.out.printf("%s%s🍳 [ORDER_ACCEPTED] Заказ #%d | %s | блюд: %d%s%n",
                CYAN, time, order.getId(), vipStatus, order.getDishesOrdered().size(), RESET);

        System.out.println("кол-во блюд в заказе: " + order.getDishesOrdered().size());

        CountDownLatch startLatch = new CountDownLatch(order.getDishesOrdered().size());

        generateCookingTasks(order, startLatch);

        System.out.printf("%s%s⏳ [WAIT_CREATION] Заказ #%d | Ожидание создания всех задач...%s%n",
                CYAN, time, order.getId(), RESET);

        startLatch.await();

        System.out.printf("%s%s🚀 [TASKS_STARTED] Заказ #%d | Задачи были созданы и запущены...%s%n",
                GREEN, time, order.getId(), RESET);

        List<? extends CompletableFuture<? extends Dish>> resultFutures = tasksByOrderId.get(order.getId())
                .stream()
                .map(CookingTask::getResultFuture)
                .toList();

        List<Dish> dishesGot = new ArrayList<>();

        try {
            for(CompletableFuture<? extends Dish> f : resultFutures) {
                dishesGot.add(f.get());
            }

            order.setDishedGot(dishesGot);
            return CompletableFuture.completedFuture(order);

        } catch (ExecutionException e) {
            throw new RuntimeException(e);

        } finally {
            tasksByOrderId.remove(order.getId());
        }
    }

    private void generateCookingTasks(Order order, CountDownLatch startLatch) {
        int orderId = order.getId();
        boolean isVip = order.isVip();

        String time = LocalDateTime.now().format(TIME_FORMATTER);


        for(Dish dish : order.getDishesOrdered()) {
            switch (dish.getType()) {
                case PIZZA -> {
                    System.out.printf("%s%s➕ [TASK_CREATED] Заказ #%d | Тип: PIZZA | Блюдо: '%s'%s%n",
                            CYAN, time, orderId, dish.getName(), RESET);

                    PizzaTask task = new PizzaTask((Pizza)dish, orderId, ovenPool, isVip, PIZZA_RETRIES, startLatch);
                    tasksByOrderId.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
                case DRINK -> {
                    System.out.printf("%s%s➕ [TASK_CREATED] Заказ #%d | Тип: DRINK | Блюдо: '%s'%s%n",
                            CYAN, time, orderId, dish.getName(), RESET);

                    DrinkTask task = new DrinkTask((Drink) dish, orderId, drinkPool, isVip, startLatch);
                    tasksByOrderId.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
                case DESSERT -> {
                    System.out.printf("%s%s➕ [TASK_CREATED] Заказ #%d | Тип: DESSERT | Блюдо: '%s'%s%n",
                            CYAN, time, orderId, dish.getName(), RESET);

                    DessertTask task = new DessertTask((Dessert) dish, orderId, dessertPool, isVip, startLatch);
                    tasksByOrderId.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
            }
        }
    }

    private boolean hasFreeOvenSlot() {
        return runningPizzaTasks.size() < OVEN_POOL_SIZE;
    }

    // TODO механизм удаления из running tasks задач со статусом НЕ running
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

    private void removeInactiveTasks() {
        runningPizzaTasks.removeIf(task -> task.getTaskState() != TaskState.RUNNING);
    }

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
