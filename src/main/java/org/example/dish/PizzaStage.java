package org.example.dish;

public enum PizzaStage {
    NONE,
    DOUGH,      // замес теста
    BAKING,     // выпечка
    DONE,       // завершена
    FIRED,      // сгорела
    FAILED      // не получилось переделать
}
