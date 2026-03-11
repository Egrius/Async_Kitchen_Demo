package org.example;

public class OrderService {

    private static volatile OrderService INSTANCE = null;

    private OrderService() {

    }

    public static OrderService getInstance() {
        if(INSTANCE == null) {
            synchronized (OrderService.class) {
                INSTANCE = new OrderService();
            }
        }
        return INSTANCE;
    }

}
