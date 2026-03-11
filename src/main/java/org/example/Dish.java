package org.example;

import java.util.concurrent.atomic.AtomicInteger;

public class Dish {
    private static AtomicInteger counter = new AtomicInteger(0);
    private int id;
    private DishType type;
    private String name;
    private int cookingTime;
    private boolean isReady = false;

    public Dish(DishType type, String name, int cookingTime) {
        id = counter.incrementAndGet();
        this.type = type;
        this.name = name;
        this.cookingTime = cookingTime;
    }

    public DishType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public int getCookingTime() {
        return cookingTime;
    }

    public boolean isReady() {
        return isReady;
    }

    public void setIsReady(boolean isReady) {
        this.isReady = isReady;
    }

    public int getId() {
        return id;
    }

    @Override
    public String toString() {
        return "Dish{" +
                "type=" + type +
                ", name='" + name + '\'' +
                ", cookingTime=" + cookingTime +
                ", isReady=" + isReady +
                '}';
    }
}
