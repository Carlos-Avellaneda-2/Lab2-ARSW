package co.eci.snake.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates pause/resume operations across all snake runner threads without relying on busy-waiting.
 */
public final class GamePauseController {
  private final AtomicInteger activeRunners = new AtomicInteger();
  private volatile boolean paused = false;
  private CountDownLatch pauseLatch = new CountDownLatch(0);

  public void registerRunner() {
    activeRunners.incrementAndGet();
  }

  public void deregisterRunner() {
    CountDownLatch latchToCountDown = null;
    synchronized (this) {
      activeRunners.updateAndGet(curr -> Math.max(0, curr - 1));
      if (paused && pauseLatch.getCount() > 0) {
        latchToCountDown = pauseLatch;
      }
    }
    if (latchToCountDown != null) {
      latchToCountDown.countDown();
    }
  }

  public synchronized void pauseAll() {
    if (paused) return;
    paused = true;
    pauseLatch = new CountDownLatch(activeRunners.get());
    if (pauseLatch.getCount() == 0) {
      notifyAll();
    }
  }

  public void awaitPauseCompletion() throws InterruptedException {
    CountDownLatch latchSnapshot;
    synchronized (this) {
      if (!paused) return;
      latchSnapshot = pauseLatch;
    }
    latchSnapshot.await();
  }

  public void awaitIfPaused() throws InterruptedException {
    CountDownLatch latchSnapshot;
    synchronized (this) {
      if (!paused) return;
      latchSnapshot = pauseLatch;
      if (latchSnapshot.getCount() > 0) {
        latchSnapshot.countDown();
      }
      while (paused) {
        wait();
      }
      return;
    }
  }

  public synchronized void resumeAll() {
    if (!paused) return;
    paused = false;
    notifyAll();
  }

  public synchronized boolean isPaused() {
    return paused;
  }
}
