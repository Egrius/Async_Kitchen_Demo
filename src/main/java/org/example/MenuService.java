package org.example;


import org.example.dish.Dessert;
import org.example.dish.Dish;
import org.example.dish.Drink;
import org.example.dish.Pizza;

import java.util.Map;

public class MenuService {
    private static Map<Long, String> menu = Map.of(
            1L, "Пицца_Пепперонни",
            2L, "Пицца_Гавайская",
            3L, "Напиток_Кока-Кола",
            4L, "Напиток_Спрайт",
            5L, "Десерт_Чизкейк",
            6L, "Десерт_Пирожное",
            7L, "Пицца_Мясная"
    );

    public static int menuSize = menu.size();

    public MenuService() {

    }

    public Dish selectFromMenu(long option) {

        if(option <= menuSize && option > 0) {
            String dishNameType = menu.get(option);

            DishType dishType = DishType.fromAbbr(dishNameType.split("_")[0]);

            String dishName = dishNameType.split("_")[1];

            int cookingTime;

            switch (dishType){
                case PIZZA  ->  {return new Pizza(dishName); }
                case DESSERT ->   {return new Dessert(dishName); }
                case DRINK -> {return new Drink(dishName); }
                default -> {
                    return null;
                }
            }

        }
        return null;
    }
}
