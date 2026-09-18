package rps.ui;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * One entry point for "run a DB call off the EDT with a smooth busy state" — used at
 * every point in the app that touches the database, so the UI never looks hung.
 *
 * Two timers prevent flicker: the overlay only appears if the work takes longer than
 * SHOW_DELAY_MS (so a 20ms local query never flashes anything), and once shown it stays
 * up for at least MIN_VISIBLE_MS (so a 160ms operation doesn't produce a 10ms blink).
 */
public final class Busy {

    private static final int SHOW_DELAY_MS = 150;
    private static final int MIN_VISIBLE_MS = 400;

    private Busy() {}

    public static <T> Task<T> call(JComponent owner, Callable<T> work) {
        return new Task<>(owner, work);
    }

    public static final class Task<T> {
        private final JComponent owner;
        private final Callable<T> work;
        private Consumer<T> onSuccess = v -> {};
        private Consumer<Exception> onError = Busy::defaultErrorDialog;
        private String message = "Working…";

        Task(JComponent owner, Callable<T> work) {
            this.owner = owner;
            this.work = work;
        }

        public Task<T> message(String m) { this.message = m; return this; }
        public Task<T> onSuccess(Consumer<T> c) { this.onSuccess = c; return this; }
        public Task<T> onError(Consumer<Exception> c) { this.onError = c; return this; }

        public void start() {
            assert SwingUtilities.isEventDispatchThread();
            BusyOverlay overlay = BusyOverlay.forComponent(owner);
            long[] shownAt = {0L};

            Timer showTimer = new Timer(SHOW_DELAY_MS, e -> {
                if (overlay != null) overlay.acquire(message);
                shownAt[0] = System.currentTimeMillis();
            });
            showTimer.setRepeats(false);
            showTimer.start();

            new SwingWorker<T, Void>() {
                @Override
                protected T doInBackground() throws Exception {
                    return work.call();
                }

                @Override
                protected void done() {
                    showTimer.stop();
                    Runnable finish = () -> {
                        if (shownAt[0] != 0 && overlay != null) overlay.release();
                        try {
                            onSuccess.accept(get());
                        } catch (ExecutionException ex) {
                            Throwable cause = ex.getCause();
                            onError.accept(cause instanceof Exception e2 ? e2 : new RuntimeException(cause));
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    };
                    if (shownAt[0] == 0) {
                        finish.run();
                        return;
                    }
                    long visible = System.currentTimeMillis() - shownAt[0];
                    if (visible >= MIN_VISIBLE_MS) {
                        finish.run();
                    } else {
                        Timer hold = new Timer((int) (MIN_VISIBLE_MS - visible), e -> finish.run());
                        hold.setRepeats(false);
                        hold.start();
                    }
                }
            }.execute();
        }
    }

    private static void defaultErrorDialog(Exception e) {
        JOptionPane.showMessageDialog(null, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
}
