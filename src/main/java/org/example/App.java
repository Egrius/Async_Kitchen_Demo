package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args ) throws InterruptedException {
        MenuService menuService = new MenuService();
        OrderService orderService = OrderService.getInstance();
        KitchenService kitchen = KitchenService.getInstance();

        Random random = new Random();
        List<Thread> threads = new ArrayList<>();
        List<Thread> clientThreads = new ArrayList<>();

        for (int i = 0; i < 10; i++) {

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            Thread client = new Thread(() -> {

                int dishesCount = random.nextInt(1, 11);
                List<Dish> dishes = new ArrayList<>();

                for(int j = 0; j < dishesCount; j++) {
                    long dish = random.nextLong(1, MenuService.menuSize + 1);
                    dishes.add(menuService.selectFromMenu(dish));
                }

                boolean isVip = Math.random() <= 0.2; // 20% випов

                Order compiled = orderService.compileOrder(dishes,isVip);
                System.out.println("\n --------------------\n[ Заказ "
                        + compiled.getId() + " ] СОБРАН (всего "+ compiled.getDishesOrdered().size()
                        + "): \n" + compiled.getDishesOrdered()
                        + "\n --------------------\n ");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }

            });
            threads.add(client);
            client.start();

        }

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        Thread.sleep(30000);
        kitchen.shutdown();
        orderService.shutdown();


    }
}
