package org.example;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderService {

    private static volatile OrderService INSTANCE = null;

    private static final AtomicInteger counter = new AtomicInteger(0);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final BlockingQueue<Order> incomingOrders = new LinkedBlockingQueue<>();
    private final KitchenService kitchenService = KitchenService.getInstance();

    private final Object lock = new Object();

    private OrderService() {
        // Запуск шедулера на просмотр очереди
        // Возможно стоит добавить взятие всей имеющейся очереди,
        // либо оставить так, что каждые 3 секунды следующий

        // Принято решение сделать снимком

        scheduler.scheduleWithFixedDelay(() -> {
            Queue<Order> snapshot = getSnapshot();
            snapshot.forEach(order -> {
                kitchenService.acceptOrder(order)
                        .thenAccept(readyOrder ->
                                System.out.println("\n✅ Заказ " + readyOrder.getId() + " готов!")
                        )
                        .exceptionally(e -> {
                            System.out.println("\n⛔ Заказ " + order.getId() + " не выполнен: " + e.getMessage());
                            return null;
                        });
            });
        }, 3, 5, TimeUnit.SECONDS);
    }

    public static OrderService getInstance() {
        if(INSTANCE == null) {
            synchronized (OrderService.class) {
                INSTANCE = new OrderService();
            }
        }
        return INSTANCE;
    }

    // Сборка заказа на основе переданных блюд
    public Order compileOrder(List<Dish> dishes, boolean isVip) {
        Order newOrder = new Order(isVip, dishes);
        incomingOrders.add(newOrder);
        return newOrder;
    }

    private Queue<Order> getSnapshot() {

        Queue<Order> snapshot = new LinkedList<>();
        System.out.println("[СИСТЕМА]: ГОТОВИМСЯ К СНИМКУ ОЧЕРЕДИ, ТЕКУЩИЙ РАЗМЕР ОЧЕРЕДИ: " + incomingOrders.size());

        synchronized (lock) { // Возможно излишне, шедулер один
            int size = incomingOrders.size();

            for(int i = 0; i < size; i++) {

                snapshot.add(incomingOrders.poll());
            }
        }
        System.out.println("[СИСТЕМА]: СДЕЛАН СНИМОК ОЧЕРЕДИ - " + snapshot);
        return snapshot;
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
