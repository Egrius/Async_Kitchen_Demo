package org.example.dish;

import org.example.DishType;

import java.util.concurrent.atomic.AtomicInteger;


public abstract class Dish {
    private static AtomicInteger counter = new AtomicInteger(0);
    private final int id;
    private final String name;
    private final int cookingTime;
    private boolean isReady = false;

    public Dish(String name, int cookingTime) {
        this.id = counter.incrementAndGet();
        this.name = name;
        this.cookingTime = cookingTime;
    }

    public abstract DishType getType();

    public static AtomicInteger getCounter() {
        return counter;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getCookingTime() {
        return cookingTime;
    }

    public void setReady(boolean ready) {
        isReady = ready;
    }

    public boolean isReady() {
        return isReady;
    }
}