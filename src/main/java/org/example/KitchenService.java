package org.example;

import org.example.dish.Dish;
import org.example.task.CookingTask;

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


    private static final ExecutorService ovenPool = Executors.newFixedThreadPool(2);
    private static final ExecutorService drinkPool = Executors.newSingleThreadExecutor();
    private static final ExecutorService dessertPool = Executors.newSingleThreadExecutor();

    /*
        Здесь хранятся все выполняющиеся задачи, благодаря чему можно прерывать выполняющиеся и всунуть випа
     */
    private static Map<Integer, List<CookingTask>> runningTasks = new ConcurrentHashMap<>();

    private PriorityBlockingQueue<CookingTask> canceledTasks = new PriorityBlockingQueue<>(100, Comparator.comparing(CookingTask::getOrderId));

    public static KitchenService getInstance() {
        if(INSTANCE == null) {
            synchronized (KitchenService.class) {
                INSTANCE = new KitchenService();
            }
        }
        return INSTANCE;
    }

    private KitchenService() {

    }

    public CompletableFuture<Order> acceptOrder(Order order) {
         /* КАК ПРИНИМАЕМ ЗАКАЗ:
            ЕСЛИ ПРИШЕЛ VIP:
            - вклиниться в пул для пиццы, отменить выполняющуюся там задачу
            - поставить приоритет выполнения остальных задач випа выше обычных

            ЕСЛИ ПРИШЕЛ ОБЫЧНЫЙ КЛИЕНТ:
            - закинуть задачи в очередь и вернуть по готовности

            ОБЩАЯ МЕХАНИКА:
            1) разбили заказ на задачи
            2) закинули задачи в running
            3) по готовности задач отдаем заказ

            главный вопрос пока что это как мне менеджерить выполнение задач, по идее можно через foreach по таскам,
            забрать фьчеры и ждать allof
         */

//        List<Dish> orderedDishes = order.getDishesOrdered();
//
//        System.out.printf("%n --- Заказ %d принят на кухню! (всего блюд: %d) ---", order.getId(), orderedDishes.size());
//
//        List<CompletableFuture<Dish>> currentOrderAllFutures = new ArrayList<>();
//
//        orderedDishes.forEach(dish -> {
//                    int orderId = order.getId();
//                    boolean isVip = order.isVip();
//                    switch (dish.getType()) {
//                        case PIZZA -> {
//                            System.out.println("-- [заказ "
//                                    + orderId
//                                    + "]: Пицца '" + dish.getName() + "' добавляется в очередь на обработку");
//
//                            CookingTask task = new CookingTask(makePizzaWithRetries(dish, 2, orderId), dish, orderId, ovenPool, isVip);
//                            // добавить в мапу ЗДЕСЬ!
//                            runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
//                            currentOrderAllFutures.add(task.getFuture());
//                        }
//                        case DRINK -> {
//                            System.out.println("-- [заказ "
//                                    + orderId
//                                    + "]: Напиток '" + dish.getName() + "' добавляется в очередь на обработку");
//
//                            CookingTask task = new CookingTask(makeDrink(dish, orderId), dish, orderId, drinkPool, isVip);
//                            runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
//                            currentOrderAllFutures.add(task.getFuture());
//                        }
//                        case DESSERT -> {
//
//                            System.out.println("-- [заказ "
//                                    + orderId
//                                    + "]: Дессерт '" + dish.getName() + "' добавляется в очередь на обработку");
//
//                            CookingTask task = new CookingTask(makeDessert(dish, orderId), dish, orderId, dessertPool, isVip);
//                            runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
//                            currentOrderAllFutures.add(task.getFuture());
//                        }
//                    };
//                }
//        );
//
//        return CompletableFuture.allOf(currentOrderAllFutures.toArray(new CompletableFuture[0]))
//                .thenApply(v -> {
//                    List<Dish> readyDishes = currentOrderAllFutures.stream()
//                            .map(CompletableFuture::join)
//                            .filter(Dish::isReady)
//                            .toList();
//                    order.setDishedGot(readyDishes);
//                    return order;
//                });
    }

    private boolean hasFreeSlot(DishType dishType) {
        long activeCount = runningTasks.values()
                .stream()
                .flatMap(Collection::stream)
                .filter(task -> task.getDish().getType() == dishType)
                .filter(CookingTask::isRunning)
                .filter( p -> !p.isVip())
                .count();

        if(dishType == DishType.PIZZA) return activeCount < 2; // ПОКА ЧТО ХАРДКОД!
        else return activeCount < 1;
    }

    private void findAndCancelTask(DishType type) {
        for (List<CookingTask> tasks : runningTasks.values()) {
            Optional<CookingTask> task = tasks.stream()
                    .filter(t -> t.getDish().getType() == type)
                    .filter(CookingTask::isRunning)
                    .findFirst();
            if (task.isPresent()) {
                CookingTask taskGot = task.get();
                taskGot.cancel();
                canceledTasks.add(taskGot);
                break;
            }
        }
    }

    public void shutdown() {
        System.out.println("\n КУХНЯ ЗАКРЫВАЕТСЯ \n");

        ovenPool.shutdown();
        drinkPool.shutdown();
        dessertPool.shutdown();

        try {
            if(!ovenPool.awaitTermination(10, TimeUnit.SECONDS)) ovenPool.shutdownNow();
            if (!drinkPool.awaitTermination(10, TimeUnit.SECONDS)) drinkPool.shutdownNow();
            if (!dessertPool.awaitTermination(10, TimeUnit.SECONDS)) dessertPool.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        System.out.println("Кухня закрыта");
    }

}
