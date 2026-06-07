package org.example.dish;

import org.example.DishType;


public class Pizza extends Dish {


    public Pizza(String name) {
        super(name, 3);
    }

    @Override
    public String toString() {
        return "Pizza{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", cookingTime=" + cookingTime +
                '}';
    }

    @Override
    public DishType getType() {
        return DishType.PIZZA;
    }
}