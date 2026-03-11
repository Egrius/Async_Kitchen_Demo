package org.example;

public enum DishType {
    PIZZA("ПИЦЦА"), DRINK("НАПИТОК"), DESSERT("ДЕСЕРТ");

    private final String abbr;

    DishType(String abbr) {
        this.abbr = abbr;
    }

    public String getAbbr() {
        return abbr;
    }

    public static DishType fromAbbr(String abbr) {
        String upper = abbr.toUpperCase();
        switch (upper) {
            case "ПИЦЦА" -> { return DishType.PIZZA; }
            case "НАПИТОК" -> { return DishType.DRINK; }
            case "ДЕСЕРТ" -> { return DishType.DESSERT; }
        }
        return null;
    }
}
