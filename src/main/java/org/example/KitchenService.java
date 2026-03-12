package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class KitchenService {

    private static volatile KitchenService INSTANCE = null;

    private static ExecutorService ovenPool = Executors.newFixedThreadPool(2);
    private static ExecutorService drinkPool = Executors.newSingleThreadExecutor();
    private static ExecutorService dessertPool = Executors.newSingleThreadExecutor();

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
        List<Dish> orderedDishes = order.getDishesOrdered();
        System.out.printf("%n --- Заказ %d принят на кухню! (всего блюд: %d) ---", order.getId(), orderedDishes.size());
        List<CompletableFuture<Dish>> allFutures = new ArrayList<>();


        orderedDishes.forEach(dish -> {
                    int orderId = order.getId();
                    switch (dish.getType()) {
                        case PIZZA -> {
                            System.out.println("-- [заказ "
                                    + orderId
                                    + "]: Пицца '" + dish.getName() + "' добавляется в очередь на обработку");
                            allFutures.add(makePizzaWithRetries(dish, 2, orderId));
                        }
                        case DRINK -> {
                            System.out.println("-- [заказ "
                                    + orderId
                                    + "]: Напиток '" + dish.getName() + "' добавляется в очередь на обработку");
                            allFutures.add(makeDrink(dish, orderId));
                        }
                        case DESSERT -> {

                            System.out.println("-- [заказ "
                                    + orderId
                                    + "]: Дессерт '" + dish.getName() + "' добавляется в очередь на обработку");
                            allFutures.add(makeDessert(dish, orderId));
                        }
                    };
                }
        );

        return CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Dish> readyDishes = allFutures.stream()
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
