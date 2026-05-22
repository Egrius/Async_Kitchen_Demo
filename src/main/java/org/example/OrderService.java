package org.example;

import org.example.dish.Dish;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderService {

    private static volatile OrderService INSTANCE = null;
    private final int SNAPSHOT_SIZE = 5;

    private static final AtomicInteger counter = new AtomicInteger(0);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final PriorityBlockingQueue<Order> incomingOrders =
            new PriorityBlockingQueue<>(100,
                (o1, o2) -> {
                    if(o1.isVip() && o2.isVip()) return 0;
                    else if (o1.isVip() && !o2.isVip()) return -1;
                    else if (!o1.isVip() && o2.isVip()) return 1;
                    else return Integer.compare(o1.getId(), o2.getId());
                });

    private final KitchenService kitchenService = KitchenService.getInstance();

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final String RESET = "\u001B[0m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";

    private OrderService() {
        // Запуск шедулера на просмотр очереди
        // Принято решение сделать снимком

        scheduler.scheduleWithFixedDelay(() -> {
            try{

                String time = LocalDateTime.now().format(TIME_FORMATTER);
                System.out.printf("%s%s🎬 [SCHEDULER_BEFORE_SNAPSHOT_TICK] Заказов в очереди: %d%s%n",
                        CYAN, time, incomingOrders.size(), RESET);

                Queue<Order> snapshot = getSnapshot();

                snapshot.forEach(order -> {
                    try {
                        kitchenService.acceptOrder(order)
                                .thenAccept(readyOrder -> {

                                    System.out.printf("%s%s🎉 [ORDER_COMPLETE] Заказ #%d | Готово блюд: %d | Время: ???%s%n",
                                            GREEN, time, readyOrder.getId(), readyOrder.getDishesGot().size(), RESET);

                                })
                                .exceptionally(e -> {

                                    System.out.printf("%s%s⛔ [ORDER_FAILED] Заказ #%d не выполнен: %s%s%n",
                                            YELLOW, time, order.getId(), e.getMessage(), RESET);

                                    return null;
                                });
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception e) {
                System.err.println("Ошибка в шедулере OrderService: " + e.getMessage());
                e.printStackTrace();
            }
        }, 3, 4, TimeUnit.SECONDS);
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

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String vipStatus = isVip ? "да" : "нет";
        System.out.printf("%s%s📦 [ORDER_CREATED] Заказ #%d | VIP: %s | блюд: %d%s%n",
                BLUE, time, newOrder.getId(), vipStatus, dishes.size(), RESET);

        incomingOrders.add(newOrder);
        return newOrder;
    }

    private Queue<Order> getSnapshot() {

        Queue<Order> snapshot = new LinkedList<>();

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s📸 [SNAPSHOT] Размер очереди: %d%s%n", CYAN, time, incomingOrders.size(), RESET);

        int elementsToTake = Math.min(SNAPSHOT_SIZE, incomingOrders.size());

        for(int i = 0; i < elementsToTake; i++) {
            snapshot.add(incomingOrders.poll());
        }

        System.out.printf("%s%s📸 [SNAPSHOT] Взято заказов: %d %s%s%n",
                CYAN, time, snapshot.size(), snapshot, RESET);
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
