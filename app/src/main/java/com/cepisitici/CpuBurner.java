package com.cepisitici;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps every selected CPU core busy with floating point + memory bandwidth work.
 * When throttled the worker threads back off until the battery cools down.
 */
public class CpuBurner {

    private static volatile double sink;

    private final int threadCount;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private volatile boolean throttled;

    public CpuBurner(int threadCount) {
        this.threadCount = Math.max(1, threadCount);
    }

    public void start() {
        running = true;
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(this::burnLoop, "cpu-burner-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
    }

    public void setThrottled(boolean throttled) {
        this.throttled = throttled;
    }

    private void burnLoop() {
        double x = Thread.currentThread().getId() * 0.0001 + 1.2345;
        double y = 0.987654321;
        double[] memory = new double[64 * 1024];
        int index = 0;

        while (running) {
            if (throttled) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            for (int i = 0; i < 50000; i++) {
                x = Math.sin(x * 1.000001 + 0.1000001) + Math.cos(x * 0.999999 + y);
                y = Math.sqrt(Math.abs(y) + 1.0) + 0.000001;
            }

            for (int i = 0; i < memory.length; i++) {
                memory[i] = memory[i] * 1.0000001 + y;
            }

            sink = x + y + memory[index & (memory.length - 1)];
            index++;

            if (Double.isNaN(x) || Double.isInfinite(x)) {
                x = 1.2345;
            }
            if (Double.isNaN(y) || Double.isInfinite(y)) {
                y = 0.987654321;
            }
        }
    }

    public void stop() {
        running = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }
}
