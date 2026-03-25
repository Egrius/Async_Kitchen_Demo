package org.example.dish;

import org.example.DishType;

public class Dessert extends Dish {
    public Dessert(String name) {
        super(name, 2);
    }

    @Override
    public DishType getType() {
        return DishType.DESSERT;
    }
}