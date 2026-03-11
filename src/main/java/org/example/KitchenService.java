package org.example;

public class KitchenService {

    private static volatile KitchenService INSTANCE = null;

    public static KitchenService getInstance() {
        if(INSTANCE == null) {
            synchronized (KitchenService.class) {
                INSTANCE = new KitchenService();
            }
        }
        return INSTANCE;
    }

}
