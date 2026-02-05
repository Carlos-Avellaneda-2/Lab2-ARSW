package co.eci.snake.concurrency;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Snake;

public final class SnakeRunner implements Runnable {
  private final Snake snake;
  private final Board board;
  private final int baseSleepMs = 80;
  private final int turboSleepMs = 40;
  private final GamePauseController pauseController;
  private final SnakeLifecycleListener lifecycleListener;
  private final AtomicBoolean alive = new AtomicBoolean(true);
  private int turboTicks = 0;

  public SnakeRunner(Snake snake, Board board, GamePauseController pauseController, SnakeLifecycleListener lifecycleListener) {
    this.snake = Objects.requireNonNull(snake, "snake");
    this.board = Objects.requireNonNull(board, "board");
    this.pauseController = Objects.requireNonNull(pauseController, "pauseController");
    this.lifecycleListener = lifecycleListener;
  }

  @Override
  public void run() {
    pauseController.registerRunner();
    try {
      while (!Thread.currentThread().isInterrupted() && alive.get()) {
        pauseController.awaitIfPaused();
        maybeTurn();
        var res = board.step(snake);
        if (res == Board.MoveResult.HIT_OBSTACLE) {
          handleDeath();
          break;
        } else if (res == Board.MoveResult.ATE_TURBO) {
          turboTicks = 100;
        }
        int sleep = (turboTicks > 0) ? turboSleepMs : baseSleepMs;
        if (turboTicks > 0) turboTicks--;
        Thread.sleep(sleep);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      pauseController.deregisterRunner();
    }
  }

  private void handleDeath() {
    if (alive.compareAndSet(true, false) && lifecycleListener != null) {
      lifecycleListener.onDeath(snake);
    }
  }

  private void maybeTurn() {
    double p = (turboTicks > 0) ? 0.05 : 0.10;
    if (ThreadLocalRandom.current().nextDouble() < p) randomTurn();
  }

  private void randomTurn() {
    var dirs = Direction.values();
    snake.turn(dirs[ThreadLocalRandom.current().nextInt(dirs.length)]);
  }
}
