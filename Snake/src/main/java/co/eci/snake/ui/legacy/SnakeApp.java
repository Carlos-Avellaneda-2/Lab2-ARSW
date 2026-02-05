package co.eci.snake.ui.legacy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import co.eci.snake.concurrency.GamePauseController;
import co.eci.snake.concurrency.SnakeRunner;
import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Position;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.GameClock;

public final class SnakeApp extends JFrame {

  private final Board board;
  private final GamePanel gamePanel;
  private final JButton actionButton;
  private final GameClock clock;
  private final GamePauseController pauseController;
  private final JLabel statsLabel;
  private final java.util.List<Snake> snakes = new java.util.ArrayList<>();
  private final java.util.List<SnakeStatus> snakeStatuses = new java.util.ArrayList<>();
  private final Map<Snake, SnakeStatus> statusBySnake = new ConcurrentHashMap<>();
  private final AtomicLong deathSequence = new AtomicLong();

  public SnakeApp() {
    super("The Snake Race");
    this.board = new Board(35, 28);
    this.pauseController = new GamePauseController();
    this.statsLabel = new JLabel("Juego en ejecución");
    statsLabel.setHorizontalAlignment(SwingConstants.CENTER);

    int N = Integer.getInteger("snakes", 2);
    for (int i = 0; i < N; i++) {
      int x = 2 + (i * 3) % board.width();
      int y = 2 + (i * 2) % board.height();
      var dir = Direction.values()[i % Direction.values().length];
      var snake = Snake.of(x, y, dir);
      snakes.add(snake);
      var status = new SnakeStatus(snake, i);
      snakeStatuses.add(status);
      statusBySnake.put(snake, status);
    }

    this.gamePanel = new GamePanel(board, () -> snakes);
    this.actionButton = new JButton("Action");

    setLayout(new BorderLayout());
    add(gamePanel, BorderLayout.CENTER);
    var controlPanel = new JPanel(new BorderLayout());
    controlPanel.add(actionButton, BorderLayout.WEST);
    controlPanel.add(statsLabel, BorderLayout.CENTER);
    add(controlPanel, BorderLayout.SOUTH);

    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    pack();
    setLocationRelativeTo(null);

    this.clock = new GameClock(60, () -> SwingUtilities.invokeLater(gamePanel::repaint));

    var exec = Executors.newVirtualThreadPerTaskExecutor();
    snakes.forEach(s -> exec.submit(new SnakeRunner(s, board, pauseController, this::handleSnakeDeath)));

    actionButton.addActionListener((ActionEvent e) -> togglePause());

    gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("SPACE"), "pause");
    gamePanel.getActionMap().put("pause", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        togglePause();
      }
    });

    var player = snakes.get(0);
    InputMap im = gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
    ActionMap am = gamePanel.getActionMap();
    im.put(KeyStroke.getKeyStroke("LEFT"), "left");
    im.put(KeyStroke.getKeyStroke("RIGHT"), "right");
    im.put(KeyStroke.getKeyStroke("UP"), "up");
    im.put(KeyStroke.getKeyStroke("DOWN"), "down");
    am.put("left", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.LEFT);
      }
    });
    am.put("right", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.RIGHT);
      }
    });
    am.put("up", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.UP);
      }
    });
    am.put("down", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.DOWN);
      }
    });

    if (snakes.size() > 1) {
      var p2 = snakes.get(1);
      im.put(KeyStroke.getKeyStroke('A'), "p2-left");
      im.put(KeyStroke.getKeyStroke('D'), "p2-right");
      im.put(KeyStroke.getKeyStroke('W'), "p2-up");
      im.put(KeyStroke.getKeyStroke('S'), "p2-down");
      am.put("p2-left", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.LEFT);
        }
      });
      am.put("p2-right", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.RIGHT);
        }
      });
      am.put("p2-up", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.UP);
        }
      });
      am.put("p2-down", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.DOWN);
        }
      });
    }

    setVisible(true);
    clock.start();
  }

  private void togglePause() {
    if (!pauseController.isPaused()) {
      actionButton.setText("Resume");
      clock.pause();
      pauseController.pauseAll();
      waitForFullPause();
      gamePanel.repaint();
      updateStatsLabel();
    } else {
      actionButton.setText("Action");
      statsLabel.setText("Juego en ejecución");
      pauseController.resumeAll();
      clock.resume();
    }
  }

  private void waitForFullPause() {
    try {
      pauseController.awaitPauseCompletion();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private void updateStatsLabel() {
    var longestAlive = snakeStatuses.stream()
        .filter(SnakeStatus::isAlive)
        .max(Comparator.comparingInt(SnakeStatus::length));
    var firstDead = snakeStatuses.stream()
        .filter(SnakeStatus::isDead)
        .min(Comparator.comparingLong(SnakeStatus::deathOrder));

    String aliveText = longestAlive
        .map(s -> String.format("Viva más larga: #%d (%d)", s.id(), s.length()))
        .orElse("Viva más larga: N/A");
    String deadText = firstDead
        .map(s -> String.format("Primer caída: #%d", s.id()))
        .orElse("Primer caída: N/A");
    statsLabel.setText(aliveText + " | " + deadText);
  }

  private void handleSnakeDeath(Snake snake) {
    var status = statusBySnake.get(snake);
    if (status == null) return;
    long order = deathSequence.incrementAndGet();
    if (status.markDead(order)) {
      SwingUtilities.invokeLater(() -> {
        if (pauseController.isPaused()) {
          updateStatsLabel();
        }
      });
    }
  }

  private static final class SnakeStatus {
    private final Snake snake;
    private final int id;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private volatile long deathOrder = Long.MAX_VALUE;

    private SnakeStatus(Snake snake, int id) {
      this.snake = snake;
      this.id = id;
    }

    boolean markDead(long order) {
      if (alive.compareAndSet(true, false)) {
        deathOrder = order;
        return true;
      }
      return false;
    }

    boolean isAlive() { return alive.get(); }
    boolean isDead() { return !alive.get(); }
    long deathOrder() { return deathOrder; }
    int length() { return snake.length(); }
    int id() { return id; }
  }

  public static final class GamePanel extends JPanel {
    private final Board board;
    private final Supplier snakesSupplier;
    private final int cell = 20;

    @FunctionalInterface
    public interface Supplier {
      List<Snake> get();
    }

    public GamePanel(Board board, Supplier snakesSupplier) {
      this.board = board;
      this.snakesSupplier = snakesSupplier;
      setPreferredSize(new Dimension(board.width() * cell + 1, board.height() * cell + 40));
      setBackground(Color.WHITE);
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      var g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      g2.setColor(new Color(220, 220, 220));
      for (int x = 0; x <= board.width(); x++)
        g2.drawLine(x * cell, 0, x * cell, board.height() * cell);
      for (int y = 0; y <= board.height(); y++)
        g2.drawLine(0, y * cell, board.width() * cell, y * cell);

      // Obstáculos
      g2.setColor(new Color(255, 102, 0));
      for (var p : board.obstacles()) {
        int x = p.x() * cell, y = p.y() * cell;
        g2.fillRect(x + 2, y + 2, cell - 4, cell - 4);
        g2.setColor(Color.RED);
        g2.drawLine(x + 4, y + 4, x + cell - 6, y + 4);
        g2.drawLine(x + 4, y + 8, x + cell - 6, y + 8);
        g2.drawLine(x + 4, y + 12, x + cell - 6, y + 12);
        g2.setColor(new Color(255, 102, 0));
      }

      // Ratones
      g2.setColor(Color.BLACK);
      for (var p : board.mice()) {
        int x = p.x() * cell, y = p.y() * cell;
        g2.fillOval(x + 4, y + 4, cell - 8, cell - 8);
        g2.setColor(Color.WHITE);
        g2.fillOval(x + 8, y + 8, cell - 16, cell - 16);
        g2.setColor(Color.BLACK);
      }

      // Teleports (flechas rojas)
      Map<Position, Position> tp = board.teleports();
      g2.setColor(Color.RED);
      for (var entry : tp.entrySet()) {
        Position from = entry.getKey();
        int x = from.x() * cell, y = from.y() * cell;
        int[] xs = { x + 4, x + cell - 4, x + cell - 10, x + cell - 10, x + 4 };
        int[] ys = { y + cell / 2, y + cell / 2, y + 4, y + cell - 4, y + cell / 2 };
        g2.fillPolygon(xs, ys, xs.length);
      }

      // Turbo (rayos)
      g2.setColor(Color.BLACK);
      for (var p : board.turbo()) {
        int x = p.x() * cell, y = p.y() * cell;
        int[] xs = { x + 8, x + 12, x + 10, x + 14, x + 6, x + 10 };
        int[] ys = { y + 2, y + 2, y + 8, y + 8, y + 16, y + 10 };
        g2.fillPolygon(xs, ys, xs.length);
      }

      // Serpientes
      var snakes = snakesSupplier.get();
      int idx = 0;
      for (Snake s : snakes) {
        var body = s.snapshot().toArray(new Position[0]);
        for (int i = 0; i < body.length; i++) {
          var p = body[i];
          Color base = (idx == 0) ? new Color(0, 170, 0) : new Color(0, 160, 180);
          int shade = Math.max(0, 40 - i * 4);
          g2.setColor(new Color(
              Math.min(255, base.getRed() + shade),
              Math.min(255, base.getGreen() + shade),
              Math.min(255, base.getBlue() + shade)));
          g2.fillRect(p.x() * cell + 2, p.y() * cell + 2, cell - 4, cell - 4);
        }
        idx++;
      }
      g2.dispose();
    }
  }

  public static void launch() {
    SwingUtilities.invokeLater(SnakeApp::new);
  }
}
