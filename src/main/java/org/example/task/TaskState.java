package org.example.task;

public enum TaskState {
    CREATED,      // создана, но ещё не передана в пул
    RUNNING,      // выполняется в пуле
    DISPLACED,    // вытеснена VIP (только для PizzaTask)
    COMPLETED,    // успешно завершена
    NEEDS_RETRY,
    FAILED        // завершена с ошибкой (после всех ретри)
}
