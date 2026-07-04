package org.example;

import org.example.dish.Dish;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

/**
 * <p>Сервис, отвечающий за приём, приоритезацию и диспетчеризацию заказов на кухню.</p>
 *
 * <p>Реализован как потокобезопасный синглтон с ленивой инициализацией.
 * Заказы помещаются в очередь {@link PriorityBlockingQueue}, где VIP-заказы имеют приоритет над обычными,
 * а при равном приоритете — сортируются по возрастанию ID заказа.</p>
 *
 * <p><b>Периодический снимок очереди:</b>
 * Каждые 4 секунды (с задержкой 3 секунды перед первым запуском) сервис формирует "снимок"
 * (snapshot) очереди размером {@value #SNAPSHOT_SIZE} заказов и отправляет их на выполнение
 * в {@link KitchenService}. Заказы, не попавшие в снимок, ожидают следующего тика.</p>
 *
 * <p><b>Жизненный цикл заказа:</b>
 * <ol>
 *   <li>Вызов {@link #compileOrder(List, boolean)} — заказ создаётся и помещается в очередь.</li>
 *   <li>Диспетчер (шедулер) периодически забирает из очереди до {@value #SNAPSHOT_SIZE} заказов.</li>
 *   <li>Для каждого заказа вызывается {@link KitchenService#acceptOrder(Order)}.</li>
 *   <li>Результат асинхронно логируется как успех или провал.</li>
 * </ol>
 * </p>
 *
 * <p><b>Потокобезопасность:</b>
 * <ul>
 *   <li>{@link #incomingOrders} — {@link PriorityBlockingQueue} обеспечивает безопасный доступ из нескольких потоков.</li>
 *   <li>Синглтон защищён двойной проверкой блокировки.</li>
 *   <li>{@link ScheduledExecutorService} работает в одном потоке, что исключает race condition при формировании снимка.</li>
 * </ul>
 * </p>
 *
 * @author Egrius
 * @see KitchenService
 * @see Order
 */
public class OrderService {

    private static volatile OrderService INSTANCE = null;

    /**
     * Максимальное количество заказов, извлекаемых из очереди за один такт шедулера.
     */
    private final int SNAPSHOT_SIZE = 5;

    /**
     * Шедулер с одним потоком для периодического опроса очереди и отправки заказов на кухню.
     */
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Потокобезопасная очередь с приоритетом, хранящая входящие заказы.
     * <p>Компаратор обеспечивает следующий порядок:
     * <ul>
     *   <li>VIP-заказы всегда обрабатываются раньше обычных.</li>
     *   <li>Среди VIP‑заказов — порядок по ID (чем меньше ID, тем раньше).</li>
     *   <li>Среди обычных — также по ID.</li>
     * </ul>
     * </p>
     */
    private static final PriorityBlockingQueue<Order> incomingOrders =
            new PriorityBlockingQueue<>(100,
                    (o1, o2) -> {
                        if (o1.isVip() && !o2.isVip()) return -1; // VIP раньше
                        else if (!o1.isVip() && o2.isVip()) return 1; // обычный позже
                        else return Integer.compare(o1.getId(), o2.getId());
                    });

    private final KitchenService kitchenService = KitchenService.getInstance();

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ANSI color codes for console output
    private static final String RESET = "\u001B[0m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";

    /**
     * Приватный конструктор.
     * <p>Запускает шедулер, который каждые 4 секунды (с начальной задержкой 3 секунды)
     * формирует снимок очереди и отправляет заказы на кухню.</p>
     */
    private OrderService() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                String time = LocalDateTime.now().format(TIME_FORMATTER);
                System.out.printf("%s%s🎬 [SCHEDULER_BEFORE_SNAPSHOT_TICK] Заказов в очереди: %d%s%n",
                        CYAN, time, incomingOrders.size(), RESET);

                Queue<Order> snapshot = getSnapshot();

                snapshot.forEach(order -> {
                    try {
                        kitchenService.acceptOrder(order)
                                .thenAccept(readyOrder -> {
                                    System.out.printf("%s%s🎉 [ORDER_COMPLETE] Заказ #%d | Готово блюд: %d%s%n",
                                            GREEN, time, readyOrder.getId(), readyOrder.getDishesGot().size(), RESET);
                                })
                                .exceptionally(e -> {
                                    System.out.printf("%s%s⛔ [ORDER_FAILED] Заказ #%d не выполнен: %s%s%n",
                                            YELLOW, time, order.getId(), e.getMessage(), RESET);
                                    return null;
                                });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception e) {
                System.err.println("Ошибка в шедулере OrderService: " + e.getMessage());
                e.printStackTrace(); // TODO: заменить на логгер
            }
        }, 3, 4, TimeUnit.SECONDS);
    }

    /**
     * Возвращает единственный экземпляр сервиса (потокобезопасный синглтон).
     *
     * @return экземпляр OrderService
     */
    public static OrderService getInstance() {
        if (INSTANCE == null) {
            synchronized (OrderService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OrderService();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Создаёт заказ из списка блюд и помещает его в очередь на обработку.
     *
     * @param dishes  список заказанных блюд
     * @param isVip   VIP-статус заказа (влияет на приоритет)
     * @return сформированный заказ {@link Order}
     */
    public Order compileOrder(List<Dish> dishes, boolean isVip) {
        Order newOrder = new Order(isVip, dishes);

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String vipStatus = isVip ? "да" : "нет";
        System.out.printf("%s%s📦 [ORDER_CREATED] Заказ #%d | VIP: %s | блюд: %d%s%n",
                BLUE, time, newOrder.getId(), vipStatus, dishes.size(), RESET);

        incomingOrders.add(newOrder);
        return newOrder;
    }

    /**
     * Формирует снимок очереди — извлекает до {@link #SNAPSHOT_SIZE} заказов с наивысшим приоритетом.
     * <p>Используется только внутри шедулера.</p>
     *
     * @return очередь из извлечённых заказов (размер не более {@code SNAPSHOT_SIZE})
     */
    private Queue<Order> getSnapshot() {
        Queue<Order> snapshot = new LinkedList<>();

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.printf("%s%s📸 [SNAPSHOT] Размер очереди: %d%s%n", CYAN, time, incomingOrders.size(), RESET);

        int elementsToTake = Math.min(SNAPSHOT_SIZE, incomingOrders.size());

        for (int i = 0; i < elementsToTake; i++) {
            snapshot.add(incomingOrders.poll());
        }

        System.out.printf("%s%s📸 [SNAPSHOT] Взято заказов: %d %s%s%n",
                CYAN, time, snapshot.size(), snapshot, RESET);
        return snapshot;
    }

    /**
     * Корректно завершает работу сервиса.
     * <p>Останавливает шедулер, ожидая до 5 секунд завершения текущей задачи.
     * При превышении таймаута — принудительная остановка.</p>
     */
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