package org.example.dish;

import org.example.DishType;

import java.util.concurrent.atomic.AtomicInteger;


public abstract class Dish {
    protected static AtomicInteger counter = new AtomicInteger(0);
    protected final int id;
    protected final String name;
    protected final int cookingTime;
    protected boolean isReady = false;

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