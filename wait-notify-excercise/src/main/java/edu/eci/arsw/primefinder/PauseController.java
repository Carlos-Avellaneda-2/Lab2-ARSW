package edu.eci.arsw.primefinder;

public class PauseController {
    private boolean paused = false;

    public synchronized void pauseAll() {
        paused = true;
    }

    public synchronized void resumeAll() {
        paused = false;
        notifyAll();
    }

    public synchronized void awaitIfPaused() throws InterruptedException {
        while (paused) {
            wait();
        }
    }
}
