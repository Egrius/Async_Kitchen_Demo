package org.example;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Order {
    private static AtomicInteger counter = new AtomicInteger(0);
    private int id;
    private boolean vip;
    private List<Dish> dishesOrdered;
    private List<Dish> dishesGot;

    public Order(boolean vip, List<Dish> dishesOrdered) {
        id = counter.incrementAndGet();
        this.vip = vip;
        this.dishesOrdered = dishesOrdered;
    }

    public int getId() {
        return id;
    }

    public boolean isVip() {
        return vip;
    }

    public List<Dish> getDishesOrdered() {
        return dishesOrdered;
    }

    public List<Dish> getDishesGot() {
        return dishesGot;
    }

    public void setDishedGot(List<Dish> dishesGot) {
        this.dishesGot = dishesGot;
    }

}
