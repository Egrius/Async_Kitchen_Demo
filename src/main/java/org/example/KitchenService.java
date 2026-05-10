package org.example;

import org.example.dish.*;
import org.example.task.CookingTask;
import org.example.task.DessertTask;
import org.example.task.DrinkTask;
import org.example.task.PizzaTask;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

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

    private PriorityBlockingQueue<CookingTask<? extends Dish>> recoveryQueue = new PriorityBlockingQueue<>(100,
            Comparator.comparing(CookingTask::getOrderId));

    private static final ExecutorService ovenPool = Executors.newFixedThreadPool(OVEN_POOL_SIZE);
    private static final ExecutorService drinkPool = Executors.newSingleThreadExecutor();
    private static final ExecutorService dessertPool = Executors.newSingleThreadExecutor();

    private static final ScheduledExecutorService dispatcher = new ScheduledThreadPoolExecutor(1);

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
            System.out.println("[DISPATCHER]: тик");
            CookingTask<?> cancelledTask = recoveryQueue.poll();
            while (cancelledTask != null) {
                if (hasFreeOvenSlot() && !cancelledTask.isRunning()) {
                    cancelledTask.start();
                } else {
                    recoveryQueue.offer(cancelledTask);
                    break;
                }
                cancelledTask = recoveryQueue.poll();
            }

            for(Map.Entry<Integer, List<CookingTask<? extends Dish>>> e : runningTasks.entrySet()) {
                for (CookingTask<?> task : e.getValue()) {
                    if (!task.isRunning()) {
                        if(task.isVip() && task.getDish().getType() == DishType.PIZZA) {

                            if(!hasFreeOvenSlot()) {

                                PizzaTask vipPizzaTask = (PizzaTask) task;

                                if(vipPizzaTask.getPizzaStage() == PizzaStage.NONE) {
                                    // Найти таску которая сейчас в пуле и отменить
                                    for(Map.Entry<Integer, List<CookingTask<? extends Dish>>> runnings : runningTasks.entrySet()) {
                                        runnings.getValue().stream()
                                                .filter(t -> t.isRunning() && t.getDish().getType() == DishType.PIZZA && !t.isVip())
                                                .peek(CookingTask::cancel)
                                                .findFirst()
                                                .ifPresent(a -> recoveryQueue.add(a)); // добавил в recoveryQueue
                                    }
                                    vipPizzaTask.start();
                                }
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

    // Суть этого метода - сгенерировать задачи и закинуть их в мапу, проверкой статусов и вытеснением уже занимается dispatcher
    public CompletableFuture<Order> acceptOrder(Order order) {

        System.out.printf("%n --- Заказ %d принят на кухню! (всего блюд: %d) ---",
                order.getId(), order.getDishesOrdered().size());

        generateCookingTasks(order);

        List<CookingTask<?>> currentOrderAllTasks = runningTasks.get(order.getId());

        while (currentOrderAllTasks.stream().anyMatch(t -> !t.isStarted())) {
            try {
                Thread.sleep(10); // небольшая задержка

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }


        CompletableFuture<?>[] futuresArray  = currentOrderAllTasks.stream()
                .map(CookingTask::getFuture)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futuresArray)
                .thenApply(v -> {
                    List<Dish> readyDishes = Arrays.stream(futuresArray)
                            .map(future -> (Dish) future.join())
                            .filter(Dish::isReady)
                            .toList();
                    order.setDishedGot(readyDishes);


                    return order;
                });
    }

    private void generateCookingTasks(Order order) {
        int orderId = order.getId();
        boolean isVip = order.isVip();

        for(Dish dish : order.getDishesOrdered()) {
            switch (dish.getType()) {
                case PIZZA -> {
                    System.out.println("-- [заказ "
                            + orderId
                            + "]: Пицца '" + dish.getName() + "' добавляется в очередь на обработку");

                    PizzaTask task = new PizzaTask((Pizza)dish, orderId, ovenPool, isVip, PIZZA_RETRIES);
                    runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
                   // currentOrderAllFutures.add(task.getFuture());
                }
                case DRINK -> {
                    System.out.println("-- [заказ "
                            + orderId
                            + "]: Напиток '" + dish.getName() + "' добавляется в очередь на обработку");

                    DrinkTask task = new DrinkTask((Drink) dish, orderId, drinkPool, isVip);
                    runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
                    //currentOrderAllFutures.add(task.getFuture());
                }
                case DESSERT -> {
                    System.out.println("-- [заказ "
                            + orderId
                            + "]: Дессерт '" + dish.getName() + "' добавляется в очередь на обработку");

                    DessertTask task = new DessertTask((Dessert) dish, orderId, dessertPool, isVip);
                    runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
                   // currentOrderAllFutures.add(task.getFuture());
                }
            };
        }
    }

    private boolean hasFreeOvenSlot() {
        long activeCount = runningTasks.values()
                .stream()
                .flatMap(Collection::stream)
                .filter(task -> task.getDish().getType() == DishType.PIZZA)
                .filter(CookingTask::isRunning)
                .filter( p -> !p.isVip())
                .count();

        return activeCount < OVEN_POOL_SIZE;
    }

    public void shutdown() {
        System.out.println("\n КУХНЯ ЗАКРЫВАЕТСЯ \n");

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

        System.out.println("Кухня закрыта");
    }

}
