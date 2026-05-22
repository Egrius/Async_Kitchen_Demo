package org.example;

import org.example.dish.*;
import org.example.task.CookingTask;
import org.example.task.DessertTask;
import org.example.task.DrinkTask;
import org.example.task.PizzaTask;

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

    // Здесь хранятся все выполняющиеся задачи, благодаря чему можно прерывать выполняющиеся и всунуть випа
    private static Map<Integer, List<CookingTask<? extends Dish>>> runningTasks = new ConcurrentHashMap<>();


    private PriorityBlockingQueue<PizzaTask> recoveryQueue = new PriorityBlockingQueue<>(100,
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

    /*
    Проблема: связка шедуллера на старт фьючеров и allOf в acceptOrder - нет синхронизации вследствие чего null
     */
    private Runnable runCookingTasks() {
        return () -> {

            String time = LocalDateTime.now().format(TIME_FORMATTER);

            System.out.printf("%s%s🔄 [DISPATCHER_TICK] Проверка очередей...%s%n", PURPLE, time, RESET);
            System.out.println("RECOVERY QUEUE SIZE: " + recoveryQueue.size());
            // Восстановление отменённых задач
            PizzaTask cancelledTask = recoveryQueue.poll();

            while (cancelledTask != null) {
                System.out.println("Вытянуто из очереди: " + cancelledTask);
                boolean freeSlot = hasFreeOvenSlot();
                System.out.println("hasFreeOvenSlot(): " + freeSlot);

                if (hasFreeOvenSlot() && !cancelledTask.isRunning()) {

                    System.out.printf("%s%s♻️ [RECOVERY] Восстановление задачи | Заказ #%d | Тип: %s%s%n",
                            PURPLE, time, cancelledTask.getOrderId(),
                            cancelledTask.getDish().getType(), RESET);

                    cancelledTask.resume();
                } else {
                    recoveryQueue.offer(cancelledTask);
                    break;
                }
                cancelledTask = recoveryQueue.poll();
            }

            // Запуск runningTasks
            for(Map.Entry<Integer, List<CookingTask<? extends Dish>>> e : runningTasks.entrySet()) {
                for (CookingTask<?> task : e.getValue()) {
                    if (!task.isRunning() && !task.isCancelled()) {
                        if(task.isVip() && task.getDish().getType() == DishType.PIZZA) {

                            if(!hasFreeOvenSlot()) {

                                PizzaTask vipPizzaTask = (PizzaTask) task;

                                // Найти таску которая сейчас в пуле и отменить
                                for(Map.Entry<Integer, List<CookingTask<? extends Dish>>> runnings : runningTasks.entrySet()) {
                                    runnings.getValue().stream()
                                            .filter(t -> t.isRunning() && !t.isVip())
                                            .filter(t -> t instanceof  PizzaTask)
                                            .map(t -> (PizzaTask) t)
                                            .peek(t -> {

                                                System.out.printf("%s%s⚡ [VIP_PREEMPT] VIP заказ #%d прервал задачу заказа #%d (Пицца '%s')%s%n",
                                                        YELLOW, time, task.getOrderId(), t.getOrderId(), t.getDish().getName(), RESET);

                                                t.cancel();
                                            })
                                            .findFirst()
                                            .ifPresent(a -> {

                                                System.out.printf("%s%s❌ [CANCEL] Отмена задачи | Заказ #%d | Причина: вытеснение VIP%s%n",
                                                        RED, time, a.getOrderId(), RESET);

                                                recoveryQueue.add(a);
                                            });
                                }
                                vipPizzaTask.start();
                            } else {
                                task.start();
                            }
                        } else {
                            task.start();
                        }
                    }
                }
            }
        };
    }

    // Суть этого метода - сгенерировать задачи и закинуть их в мапу,
    // проверкой статусов и вытеснением уже занимается dispatcher
    public CompletableFuture<Order> acceptOrder(Order order) throws InterruptedException {

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String vipStatus = order.isVip() ? "VIP: да" : "VIP: нет";
        System.out.printf("%s%s🍳 [ORDER_ACCEPTED] Заказ #%d | %s | блюд: %d%s%n",
                CYAN, time, order.getId(), vipStatus, order.getDishesOrdered().size(), RESET);

        System.out.println("кол-во блюд в заказе: " + order.getDishesOrdered().size());
        CountDownLatch startLatch = new CountDownLatch(order.getDishesOrdered().size());

        generateCookingTasks(order, startLatch);

        List<CookingTask<? extends Dish>> currentOrderAllTasks = runningTasks.get(order.getId());


        System.out.printf("%s%s⏳ [WAIT_START] Заказ #%d | Ожидание запуска всех задач...%s%n",
                CYAN, time, order.getId(), RESET);

        startLatch.await();

        System.out.println("Статусы готовности для задач order c id ='" + order.getId() + "', кол-во блюд: " + order.getDishesOrdered().size());
        currentOrderAllTasks.forEach(t -> {
            System.out.println();
            System.out.print(t.isStarted() + " ");
            System.out.println();
        });

        System.out.printf("%s%s🚀 [ALL_TASKS_STARTED] Заказ #%d | Готово к allOf%s%n",
                GREEN, time, order.getId(), RESET);

        CompletableFuture<CookingTask<? extends Dish>>[] futuresArray  = currentOrderAllTasks.stream()
                .map(CookingTask::getFuture)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futuresArray)
                .thenApply(v -> {
                    List<Dish> readyDishes = Arrays.stream(futuresArray)
                            .map(future -> future.join())
                            .peek(task -> runningTasks.remove(task.getOrderId(), task))
                            .map(task -> (Dish) task.getDish())
                            .filter(Dish::isReady)
                            .toList();
                    order.setDishedGot(readyDishes);


                    return order;
                });
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
                    runningTasks.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
                case DRINK -> {
                    System.out.printf("%s%s➕ [TASK_CREATED] Заказ #%d | Тип: DRINK | Блюдо: '%s'%s%n",
                            CYAN, time, orderId, dish.getName(), RESET);

                    DrinkTask task = new DrinkTask((Drink) dish, orderId, drinkPool, isVip, startLatch);
                    runningTasks.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
                case DESSERT -> {
                    System.out.printf("%s%s➕ [TASK_CREATED] Заказ #%d | Тип: DESSERT | Блюдо: '%s'%s%n",
                            CYAN, time, orderId, dish.getName(), RESET);

                    DessertTask task = new DessertTask((Dessert) dish, orderId, dessertPool, isVip, startLatch);
                    runningTasks.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(task);
                }
            }
        }
    }

    private boolean hasFreeOvenSlot() {
        long activeCount = runningTasks.values()
                .stream()
                .flatMap(Collection::stream)
                .filter(task -> task.getDish().getType() == DishType.PIZZA)
                .filter(CookingTask::isRunning)
                .peek(t -> System.out.println("Сейчас в статусе Running: " + t))
                .filter( p -> !p.isVip())
                .count();

        return activeCount < OVEN_POOL_SIZE;
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
