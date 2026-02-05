package co.eci.snake.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class Snake {
  private final Deque<Position> body = new ArrayDeque<>();
  private final ReadWriteLock bodyLock = new ReentrantReadWriteLock();
  private volatile Direction direction;
  private int maxLength = 5;

  private Snake(Position start, Direction dir) {
    body.addFirst(start);
    this.direction = dir;
  }

  public static Snake of(int x, int y, Direction dir) {
    return new Snake(new Position(x, y), dir);
  }

  public Direction direction() { return direction; }

  public void turn(Direction dir) {
    if ((direction == Direction.UP && dir == Direction.DOWN) ||
        (direction == Direction.DOWN && dir == Direction.UP) ||
        (direction == Direction.LEFT && dir == Direction.RIGHT) ||
        (direction == Direction.RIGHT && dir == Direction.LEFT)) {
      return;
    }
    this.direction = dir;
  }

  public Position head() {
    bodyLock.readLock().lock();
    try {
      return body.peekFirst();
    } finally {
      bodyLock.readLock().unlock();
    }
  }

  public Deque<Position> snapshot() {
    bodyLock.readLock().lock();
    try {
      return new ArrayDeque<>(body);
    } finally {
      bodyLock.readLock().unlock();
    }
  }

  public void advance(Position newHead, boolean grow) {
    bodyLock.writeLock().lock();
    try {
      body.addFirst(newHead);
      if (grow) maxLength++;
      while (body.size() > maxLength) body.removeLast();
    } finally {
      bodyLock.writeLock().unlock();
    }
  }

  public int length() {
    bodyLock.readLock().lock();
    try {
      return body.size();
    } finally {
      bodyLock.readLock().unlock();
    }
  }
}
