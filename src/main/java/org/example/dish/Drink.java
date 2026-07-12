package org.example.dish;

import org.example.DishType;

public class Drink extends Dish {
    public Drink(String name) {
        super(name, 1);
    }

    @Override
    public DishType getType() {
        return DishType.DRINK;
    }
}