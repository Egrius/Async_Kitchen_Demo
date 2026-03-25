package org.example;

import org.example.dish.Dessert;
import org.example.dish.Dish;
import org.example.dish.Drink;
import org.example.dish.Pizza;
import org.example.task.CookingTask;
import org.example.task.DessertTask;
import org.example.task.DrinkTask;
import org.example.task.PizzaTask;

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
    private static final Map<Order, List<CookingTask<? extends Dish>>> runningTasks = new ConcurrentHashMap<>();

    private static final PriorityBlockingQueue<CookingTask<? extends Dish>> canceledTasks = new PriorityBlockingQueue<>(100, Comparator.comparing(CookingTask::getOrderId));

    public static KitchenService getInstance() {
        if(INSTANCE == null) {
            synchronized (KitchenService.class) {
                INSTANCE = new KitchenService();
            }
        }
        return INSTANCE;
    }

    private KitchenService() { }

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

        List<CookingTask<? extends Dish>> tasks = new ArrayList<>();

        // Для каждого блюда своя задача идет в общий пул задач orderId -> tasks
        order.getDishesOrdered().forEach( d -> {
                    int orderId = order.getId();
                    boolean isVip = order.isVip();
                    switch (d.getType()) {
                        case DRINK -> {
                            tasks.add(new DrinkTask((Drink) d, orderId, drinkPool, isVip));
                        }
                        case DESSERT -> {
                            tasks.add(new DessertTask((Dessert) d, orderId, dessertPool, isVip));
                        }
                        case PIZZA -> {
                            tasks.add(new PizzaTask((Pizza) d, orderId, ovenPool, isVip, 2));
                        }
                    }
                }
        );

        // Продумать как раскидать таски так чтобы втиснуть все отдельные с випа

        if(order.isVip()) {
            System.out.println("*** ПРИШЕЛ VIP ***");

            for(CookingTask<? extends Dish> task : tasks) {
                if(task.getDish().getType() == DishType.PIZZA) {
                    synchronized (ovenPool) {
                        // посмотреть сколько сейчас запущенных, т.е которые сейчас в пуле
                        long countTakenPlacesOvenPool = countOvenPool(order);

                        if(countTakenPlacesOvenPool >= 2) {
                            findAndCancelTask(DishType.PIZZA);
                        }
                    }
                }
            }
        }

        // закинули задачи в мапу
        runningTasks.put(order, tasks);

        // Запуск задач по той же ссылке, но не через map.get()
        CompletableFuture<?>[] allFutures = tasks.stream()
                .map(CookingTask::start)
                .toArray(CompletableFuture[]::new);

        // Ждать выполнения фьючеров всех задач
        // После чего нужно вернуть собранный заказ
        return CompletableFuture.allOf(allFutures)
                .thenApply(v -> {
                    List<Dish> completedDishes = tasks.stream()
                            .map(task -> {
                                CompletableFuture<?> future = task.getFuture();
                                return (Dish) future.join();
                            })
                            .filter(Dish::isReady)
                            .toList();

                    order.setDishedGot(completedDishes);
                    return order;
                })
                .whenComplete((res, ex) -> runningTasks.remove(order.getId()));
    }

    private long countOvenPool(Order currentOrder) {
        synchronized (runningTasks) {

            return runningTasks.entrySet().stream()
                    .filter(e -> e.getKey() != currentOrder) // исключаем текущий заказ
                    .flatMap(e -> e.getValue().stream())
                    .filter(t -> t.getDish().getType() == DishType.PIZZA)
                    .filter(CookingTask::isRunning)
                    .count();
        }
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
        for (List<CookingTask<? extends Dish>> tasks : runningTasks.values()) {
            Optional<CookingTask<? extends Dish>> task = tasks.stream()
                    .filter(t -> t.getDish().getType() == type)
                    .filter(CookingTask::isRunning)
                    .findFirst();

            if (task.isPresent()) {
                CookingTask<? extends Dish> taskGot = task.get();
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
