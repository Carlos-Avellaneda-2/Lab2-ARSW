package co.eci.snake.concurrency;

import co.eci.snake.core.Snake;

@FunctionalInterface
public interface SnakeLifecycleListener {
  void onDeath(Snake snake);
}
