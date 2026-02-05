# Lab2-ARSW

**Carlos Avellaneda**

---


## Parte I — PrimeFinder

### Sincronización y pausa periódica de hilos

Para cumplir el requerimiento de pausar todos los hilos cada t milisegundos y mostrar la cantidad de números primos encontrados, se implementó una solución basada en monitores (`wait/notifyAll`) usando la clase `PauseController`.

**Ubicación de PauseController:**
`wait-notify-excercise/src/main/java/edu/eci/arsw/primefinder/PauseController.java`

**Modificaciones clave:**
- En el método `run()` de la clase `Control`, cada ciclo de t milisegundos se ejecuta:

```java
try (Scanner scanner = new Scanner(System.in)) {
	while (true) {
		Thread.sleep(TMILISECONDS);
		controller.pauseAll();
		System.out.println("----Pausado----");
		int cont = 0;
		for (PrimeFinderThread t : pft) {
			cont += t.getPrimes().size();
		}
		System.out.println("Numeros Primos: " + cont);
		System.out.println("Presiona Enter para continuar...");
		scanner.nextLine();
		controller.resumeAll();
		System.out.println("----Resumido----");
	}
}
catch (InterruptedException e) {
	Thread.currentThread().interrupt();
}
```

- En el método `run()` de cada `PrimeFinderThread`, antes de validar si un número es primo, se llama a la función de sincronización:

```java
for (int i = a; i < b; i++) {
	controller.awaitIfPaused();
	if (isPrime(i)) {
		primes.add(i);
		System.out.println(i);
	}
}
```


**Funcionamiento:**
- El hilo principal (`Control`) pausa todos los trabajadores llamando `pauseAll()` sobre el mismo monitor (`PauseController`). Los hilos trabajadores quedan bloqueados en `awaitIfPaused()` usando `wait()` hasta que el hilo principal llama `resumeAll()` y libera a todos con `notifyAll()`.
- Esto elimina la espera activa y garantiza que todos los hilos se detienen y reanudan de forma coordinada.

**Observaciones y comentarios sobre el diseño de sincronización:**
- **Lock utilizado:** El lock es el propio objeto `PauseController`, sobre el cual se sincronizan todos los métodos (`pauseAll`, `resumeAll`, `awaitIfPaused`). Esto asegura que la condición de pausa es compartida y protegida por el mismo monitor.
- **Condición:** La variable booleana `paused` es la condición que determina si los hilos deben esperar o continuar. Los trabajadores verifican esta condición dentro de un ciclo `while (paused)` en `awaitIfPaused()`.
- **Evitar lost wakeups:** El uso del ciclo `while (paused)` dentro de `awaitIfPaused()` garantiza que, aunque ocurra un despertar espurio (por ejemplo, por un `notifyAll` no relacionado), el hilo solo continuará si la condición realmente ha cambiado. Esto previene el problema de lost wakeups y asegura la correcta coordinación.


---


## Parte II — Snake Race (Java 21 + Virtual Threads)

### Análisis de concurrencia y modificaciones realizadas

**1. Autonomía de las serpientes y concurrencia**
- Cada serpiente corre en su propio virtual thread, lanzado por `newVirtualThreadPerTaskExecutor()` y ejecutando la clase `SnakeRunner`. Esto permite que cada serpiente avance y reaccione de forma independiente. ([Snake/src/main/java/co/eci/snake/ui/legacy/SnakeApp.java](Snake/src/main/java/co/eci/snake/ui/legacy/SnakeApp.java))

- El método `Board.step(Snake snake)` era originalmente sincronizado en su totalidad, lo que generaba bloqueos amplios y afectaba la escalabilidad. Se modificó para que solo las operaciones sobre las colecciones compartidas (`mice`, `obstacles`, `turbo`, `teleports`) estén protegidas por un bloque `synchronized`, mientras que el cálculo del siguiente paso y el avance de la serpiente se ejecutan fuera del bloqueo. ([Snake/src/main/java/co/eci/snake/core/Board.java](Snake/src/main/java/co/eci/snake/core/Board.java))


**Código modificado en Board.java:**
```java
public MoveResult step(Snake snake) {
		Objects.requireNonNull(snake, "snake");
		var head = snake.head();
		var dir = snake.direction();
		Position next = new Position(head.x() + dir.dx, head.y() + dir.dy).wrap(width, height);

		boolean teleported = false;
		boolean ateMouse = false;
		boolean ateTurbo = false;
		synchronized (this) {
			if (obstacles.contains(next)) return MoveResult.HIT_OBSTACLE;
			if (teleports.containsKey(next)) {
				next = teleports.get(next);
				teleported = true;
			}
			ateMouse = mice.remove(next);
			ateTurbo = turbo.remove(next);
			if (ateMouse) {
				mice.add(randomEmpty());
				obstacles.add(randomEmpty());
				if (ThreadLocalRandom.current().nextDouble() < 0.2) turbo.add(randomEmpty());
			}
		}
		snake.advance(next, ateMouse);
		if (ateTurbo) return MoveResult.ATE_TURBO;
		if (ateMouse) return MoveResult.ATE_MOUSE;
		if (teleported) return MoveResult.TELEPORTED;
		return MoveResult.MOVED;
}
```


**3. Colecciones y estructuras no seguras**
- El cuerpo de cada serpiente (`Deque<Position> body`) no es seguro para concurrencia, ya que es accedido tanto por la UI (para pintar) como por el hilo de la serpiente. Para evitar data races, se añadió un `ReadWriteLock` que protege los métodos `head()`, `snapshot()`, `advance()` y `length()`. ([Snake/src/main/java/co/eci/snake/core/Snake.java](Snake/src/main/java/co/eci/snake/core/Snake.java))

**¿Por qué esta es una región crítica?**
El cuerpo de la serpiente es modificado por el hilo de la serpiente (al avanzar) y leído por la UI (al pintar el tablero). Si ambos acceden concurrentemente sin protección, pueden ocurrir inconsistencias, lecturas corruptas o excepciones. Por eso, se usa un `ReadWriteLock` para permitir múltiples lecturas concurrentes pero garantizar exclusión mutua en las escrituras.

**Código modificado en Snake.java:**
```java
private final Deque<Position> body = new ArrayDeque<>();
private final ReadWriteLock bodyLock = new ReentrantReadWriteLock();

public Position head() {
	bodyLock.readLock().lock();
	try {
		return body.peekFirst();
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
```

**4. Eliminación de espera activa y sincronización innecesaria**
- Antes, la pausa solo afectaba el `GameClock`, pero las serpientes seguían moviéndose en segundo plano. Se implementó la clase `GamePauseController`, que coordina la pausa y reanudación de todos los hilos de serpientes usando `CountDownLatch` y `wait()/notifyAll()`, eliminando la espera activa y asegurando que la suspensión sea consistente. ([Snake/src/main/java/co/eci/snake/concurrency/GamePauseController.java](Snake/src/main/java/co/eci/snake/concurrency/GamePauseController.java))

**5. Control de vida y estadísticas**
- Se agregó un listener en `SnakeRunner` para notificar cuando una serpiente muere (al chocar con un obstáculo). Esto permite registrar el orden de muerte y calcular métricas como la "viva más larga" y la "peor serpiente". ([Snake/src/main/java/co/eci/snake/concurrency/SnakeRunner.java](Snake/src/main/java/co/eci/snake/concurrency/SnakeRunner.java))

**6. UI y consistencia visual al pausar/reanudar**
- La interfaz (`SnakeApp`) ahora coordina la pausa: detiene el `GameClock`, solicita la pausa global a `GamePauseController`, espera a que todos los hilos confirmen la detención (`awaitPauseCompletion()`), y solo entonces actualiza la UI y muestra las estadísticas. ([Snake/src/main/java/co/eci/snake/ui/legacy/SnakeApp.java](Snake/src/main/java/co/eci/snake/ui/legacy/SnakeApp.java))
- El botón **Action/Resume** controla tanto el reloj como los hilos de serpientes, evitando lecturas inconsistentes. La etiqueta inferior muestra la serpiente viva más larga y la primera caída, garantizando que la información es consistente al pausar.

**7. Robustez bajo carga y validaciones**
- El juego se probó con un número alto de serpientes (`-Dsnakes=20`), verificando que no se producen `ConcurrentModificationException`, lecturas inconsistentes ni deadlocks. Las reglas de teleports y turbo también fueron revisadas para evitar condiciones de carrera.


- `mvn -q clean verify` (dentro de `Snake/`) — compilación exitosa después de los cambios de concurrencia y UI.
  
![Snake con 20 serpientes](img/Snake%20=%2020.PNG)
---

