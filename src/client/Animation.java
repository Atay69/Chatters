package client;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.util.function.DoubleConsumer;

/**
 * Small helpers that make the window feel smooth. Everything runs on the Swing timer, so it is safe to
 * change components from the callbacks.
 */
final class Animation {
    private Animation() {
    }

    /**
     * Runs {@code step} about 60 times per second for {@code millis} milliseconds. It receives a number
     * that grows from 0 to 1 and slows down at the end ("ease out"), then {@code done} runs (may be null).
     */
    static void run(int millis, DoubleConsumer step, Runnable done) {
        long start = System.nanoTime();
        Timer timer = new Timer(15, null);
        timer.addActionListener(event -> {
            double time = Math.min(1.0, (System.nanoTime() - start) / (millis * 1_000_000.0));
            step.accept(1 - Math.pow(1 - time, 3));
            if (time >= 1.0) {
                timer.stop();
                if (done != null) {
                    done.run();
                }
            }
        });
        timer.start();
    }

    /**
     * A number that glides between 0 (mouse not over the component) and 1 (mouse over it) instead of
     * jumping. Call setHover() from the mouse listener and read level() when painting.
     */
    static final class HoverFade {
        private static final float SPEED = 0.14f; // about 7 frames = 0.1 s from one end to the other

        private final JComponent owner;
        private final Timer timer;
        private float level;
        private float target;

        HoverFade(JComponent owner) {
            this.owner = owner;
            this.timer = new Timer(15, event -> step());
        }

        float level() {
            return level;
        }

        void setHover(boolean hover) {
            target = hover ? 1 : 0;
            if (level != target && !timer.isRunning()) {
                timer.start();
            }
        }

        private void step() {
            level += Math.max(-SPEED, Math.min(SPEED, target - level));
            owner.repaint();
            if (level == target) {
                timer.stop();
            }
        }
    }
}
