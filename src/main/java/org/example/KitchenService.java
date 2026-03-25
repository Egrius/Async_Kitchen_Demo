package org.example;

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

        if(order.isVip()) {
            for (Dish dish : order.getDishesOrdered()) {

                ExecutorService pool = getPoolForDish(dish);
                DishType type = dish.getType();

                // Заблокировали пул, смотрим есть ли там какая-та задача, если есть, то её отменяем
                // и ставим свою
                synchronized (pool) {
                    if (!hasFreeSlot(type)) {
                        findAndCancelTask(type);
                    }
                }
            }
        }

        List<Dish> orderedDishes = order.getDishesOrdered();

        System.out.printf("%n --- Заказ %d принят на кухню! (всего блюд: %d) ---", order.getId(), orderedDishes.size());

        List<CompletableFuture<Dish>> currentOrderAllFutures = new ArrayList<>();

        orderedDishes.forEach(dish -> {
                    int orderId = order.getId();
                    switch (dish.getType()) {
                        case PIZZA -> {
                            System.out.println("-- [заказ "
                                    + orderId
                                    + "]: Пицца '" + dish.getName() + "' добавляется в очередь на обработку");

                            CookingTask task = new CookingTask(makePizzaWithRetries(dish, 2, orderId), dish, orderId, ovenPool);
                            // добавить в мапу ЗДЕСЬ!
                            runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
                            currentOrderAllFutures.add(task.getFuture());
                        }
                        case DRINK -> {
                            System.out.println("-- [заказ "
                                    + orderId
                                    + "]: Напиток '" + dish.getName() + "' добавляется в очередь на обработку");

                            CookingTask task = new CookingTask(makeDrink(dish, orderId), dish, orderId, drinkPool);
                            runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
                            currentOrderAllFutures.add(task.getFuture());
                        }
                        case DESSERT -> {

                            System.out.println("-- [заказ "
                                    + orderId
                                    + "]: Дессерт '" + dish.getName() + "' добавляется в очередь на обработку");

                            CookingTask task = new CookingTask(makeDessert(dish, orderId), dish, orderId, dessertPool);
                            runningTasks.computeIfAbsent(orderId, k -> new ArrayList<>()).add(task);
                            currentOrderAllFutures.add(task.getFuture());
                        }
                    };
                }
        );

        return CompletableFuture.allOf(currentOrderAllFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Dish> readyDishes = currentOrderAllFutures.stream()
                            .map(CompletableFuture::join)
                            .filter(Dish::isReady)
                            .toList();
                    order.setDishedGot(readyDishes);
                    return order;
                });
    }

    // Во время готовки пицца может подгореть, в таком случае мы делаем 2 попытки на переготовку
    // Если 2 раза сгорела, то её не добавляем в заказ
    private CompletableFuture<Dish> makePizzaWithRetries(Dish pizza, int retries, Integer orderId) {

        CookingTask currentTask = runningTasks.get(orderId).stream()
                .filter(t -> t.getDish().equals(pizza))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Не найдена задача для текущей пиццы " + pizza));

        currentTask.setRunning(true);

        return prepareDough(pizza, orderId).thenCompose(v -> bakePizza(pizza, orderId))
                .exceptionallyCompose(throwable -> {
                    System.out.println(throwable.getMessage());
                    if(retries <= 0) {
                        pizza.setIsReady(false);
                        throw new RuntimeException("[заказ %d]: 💥 Пиццу '%s' с id{%d} не удалось приготовить".formatted(orderId, pizza.getName(), pizza.getId()));
                    }
                    return makePizzaWithRetries(pizza, retries-1, orderId);
                });
    }
    
    // Для теста не будет пула, якобы его делают быстро
    private CompletableFuture<Void> prepareDough(Dish dish, Integer orderId) {
        return CompletableFuture.runAsync(() -> {
            System.out.printf("%n[заказ %d]: Начали замешивать тесто для пиццы '%s', id{%d}", orderId, dish.getName(), dish.getId());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            System.out.println("Тесто для пиццы готово");
        });
    }

    private CompletableFuture<Dish> bakePizza(Dish pizza, Integer orderId) {
        return CompletableFuture.supplyAsync(() -> {
            if(Math.random() <= 0.7) {
                throw new RuntimeException("[заказ %d]: ❌ Пицца '%s', id{%d} подгорела".formatted(orderId, pizza.getName(), pizza.getId()));
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            pizza.setIsReady(true);
            return pizza;
        }, ovenPool);
    }

    private CompletableFuture<Dish> makeDrink(Dish drink, Integer orderId) {

        CookingTask currentTask = runningTasks.get(orderId).stream()
                .filter(t -> t.getDish().equals(drink))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Не найдена задача для напитка " + drink));

        currentTask.setRunning(true);

        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.printf("%n[заказ %d]: Начали готовить напиток '%s', id{%d} %n", orderId, drink.getName(), drink.getId());
                Thread.sleep(1000);
                System.out.printf("%n[заказ %d]: напиток '%s', ГОТОВ id{%d} %n", orderId, drink.getName(), drink.getId());
                drink.setIsReady(true);
                return drink;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, drinkPool);
    }

    private CompletableFuture<Dish> makeDessert(Dish dessert, Integer orderId) {

        CookingTask currentTask = runningTasks.get(orderId).stream()
                .filter(t -> t.getDish().equals(dessert))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Не найдена задача для десерта " + dessert));

        currentTask.setRunning(true);

        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.printf("%n[заказ %d]: Начали готовить десерт '%s', id{%d} %n", orderId, dessert.getName(), dessert.getId());
                Thread.sleep(1000);
                System.out.printf("%n[заказ %d]: десерт '%s', ГОТОВ id{%d} %n", orderId, dessert.getName(), dessert.getId());
                dessert.setIsReady(true);
                return dessert;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, dessertPool);
    }

    private ExecutorService getPoolForDish(Dish dish) {
        switch (dish.getType()) {
            case DRINK -> {
                return drinkPool;
            }
            case PIZZA -> {
                return ovenPool;
            }
            case DESSERT -> {
                return dessertPool;
            }
            default -> {
                return null;
            }
        }
    }

    private boolean hasFreeSlot(DishType dishType) {
        long activeCount = runningTasks.values()
                .stream()
                .flatMap(Collection::stream)
                .filter(task -> task.getDish().getType() == dishType)
                .filter(CookingTask::isRunning)
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
                task.get().cancel();
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
