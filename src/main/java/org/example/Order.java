package org.example;

import org.example.dish.Dish;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сущность заказа. Содержит информацию о заказанных блюдах и результат выполнения.
 * <p>Каждый заказ автоматически получает уникальный идентификатор через атомарный счётчик.
 * Заказ может быть обычным или VIP (влияет на приоритет обработки).</p>
 *
 * <p><b>Жизненный цикл заказа:</b>
 * <ol>
 *   <li>Создание через {@link OrderService#compileOrder}</li>
 *   <li>Помещение в очередь {@link OrderService#incomingOrders}</li>
 *   <li>Отправка на кухню {@link KitchenService#acceptOrder}</li>
 *   <li>Приготовление блюд (асинхронные задачи)</li>
 *   <li>Установка готовых блюд через {@link #setDishedGot}</li>
 *   <li>Возврат клиенту</li>
 * </ol>
 * </p>
 *
 * @author Egrius
 * @see OrderService
 * @see KitchenService
 */
public class Order {
    private static final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Уникальный идентификатор заказа (натуральная последовательность).
     */
    private final int id;

    /**
     * VIP-статус заказа (влияет на приоритет в очереди и возможность вытеснения).
     */
    private final boolean vip;

    /**
     * Список заказанных блюд (входные данные).
     */
    private final List<Dish> dishesOrdered;

    /**
     * Список приготовленных блюд (результат выполнения).
     * Заполняется после успешного приготовления всех блюд заказа.
     */
    private List<Dish> dishesGot;

    /**
     * Конструктор заказа.
     *
     * @param vip            флаг VIP-статуса
     * @param dishesOrdered  список блюд, которые нужно приготовить
     */
    public Order(boolean vip, List<Dish> dishesOrdered) {
        this.id = counter.incrementAndGet();
        this.vip = vip;
        this.dishesOrdered = dishesOrdered;
    }

    /**
     * Возвращает уникальный идентификатор заказа.
     *
     * @return id заказа
     */
    public int getId() {
        return id;
    }

    /**
     * Проверяет, является ли заказ VIP.
     *
     * @return true, если заказ VIP
     */
    public boolean isVip() {
        return vip;
    }

    /**
     * Возвращает список заказанных блюд.
     *
     * @return неизменяемый список заказанных блюд (но может быть изменён внешне, осторожно)
     */
    public List<Dish> getDishesOrdered() {
        return dishesOrdered;
    }

    /**
     * Возвращает список приготовленных блюд.
     *
     * @return список готовых блюд (может быть null, если заказ ещё не выполнен)
     */
    public List<? extends Dish> getDishesGot() {
        return dishesGot;
    }

    /**
     * Устанавливает список приготовленных блюд после выполнения заказа.
     *
     * @param dishesGot список готовых блюд
     */
    public void setDishedGot(List<Dish> dishesGot) {
        this.dishesGot = dishesGot;
    }
}