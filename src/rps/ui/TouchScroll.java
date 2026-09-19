package rps.ui;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.function.Consumer;

/**
 * "Drag anywhere to scroll" for a touchscreen till, where dragging the actual scrollbar
 * thumb with a fingertip is impractical — reported live from the shop's counter. Windows
 * delivers a single-finger touch drag to Swing as an ordinary mouse press/drag/release
 * sequence, so this needs no native touch API, just a listener that pans the nearest
 * ancestor {@link JViewport} once the drag exceeds a small threshold — and, past that
 * basic mechanic, momentum: a flick keeps gliding and decelerates smoothly after release
 * instead of stopping dead, which is most of what separates "technically scrolls" from
 * feeling like a real touch interface.
 *
 * <p>AWT captures the mouse to whichever component received the press for the whole
 * gesture — drag and release events keep going to it even once the pointer has physically
 * moved onto a sibling — so one listener per component is enough: a swipe starting on one
 * menu tile and crossing three neighbours still scrolls smoothly the whole time, driven
 * entirely by the first tile's own listener.
 *
 * <p>Deliberately installed on specific components chosen by each call site, never walked
 * recursively over a whole subtree — a text field or a real button needs to keep its own
 * click/caret/selection behaviour untouched, and only the call site building the layout
 * knows which of its children those are.
 */
public final class TouchScroll {

    private static final int THRESHOLD_PX = 6;

    // Momentum tuning. FRICTION multiplies the velocity every tick, so it's how quickly a
    // flick decays — 0.94 reads as a natural, slightly-weighted glide rather than an abrupt
    // stop or an endless drift; TICK_MS matches a comfortable ~60fps update rate.
    private static final double FRICTION = 0.94;
    private static final int TICK_MS = 16;
    /** Below this many px/tick the glide is imperceptible — stop the timer rather than
     *  run it forever asymptotically approaching zero. */
    private static final double MIN_VELOCITY_PX_PER_TICK = 0.4;
    /** A release must be at least this fast to count as a deliberate flick worth
     *  continuing — an ordinary drag-then-lift that happened to end with a slow residual
     *  velocity should just stop where it is, not visibly "coast" a few extra pixels. */
    private static final double MIN_FLICK_PX_PER_TICK = 1.2;

    private TouchScroll() {}

    /** For a component whose tap action needs to know WHERE it was tapped (a JTable, where
     *  the row/column depend on the click point) — a movement under the threshold still
     *  fires {@code onTap} with the originating event, as an ordinary tap; anything past it
     *  pans the ancestor scroll pane instead and the tap never fires, so a swipe across
     *  table rows never also opens/edits whatever row it happened to start on. */
    public static void install(JComponent target, Consumer<MouseEvent> onTap) {
        Drag drag = new Drag(target);
        target.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { drag.start(e); }
            @Override public void mouseReleased(MouseEvent e) { drag.release(); }
            @Override public void mouseClicked(MouseEvent e) {
                if (!drag.wasDrag()) onTap.accept(e);
            }
        });
        target.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { drag.drag(e); }
        });
    }

    /** For a component with its own tap action that doesn't need the event (a menu tile,
     *  anything using a plain onClick callback) — same drag-vs-tap distinction as the
     *  {@link Consumer} overload. */
    public static void install(JComponent target, Runnable onTap) {
        install(target, (MouseEvent e) -> onTap.run());
    }

    /** For a purely decorative area (labels, gaps, panel backgrounds) with no tap action
     *  of its own — drag scrolls; a plain tap does nothing either way. */
    public static void install(JComponent target) {
        install(target, (MouseEvent e) -> {});
    }

    /**
     * For a real Swing button (a category tab, anything whose "tap" is its own built-in
     * {@code ActionListener} rather than a plain click listener this class can gate) — a
     * button fires its action through its own {@code ButtonModel} press/release tracking,
     * which an additionally-attached listener cannot simply override the way a raw
     * {@code mouseClicked} listener can. Instead, the instant a drag crosses the
     * threshold, this disarms the button's model directly so releasing over it (or over
     * any neighbour) cannot fire its action — the same "swipe across a mostly-button
     * strip must scroll, not activate whatever it passed over" guarantee as the other
     * {@code install} overloads, just reached a different way for a component this class
     * doesn't otherwise control the click-firing of.
     *
     * <p>Only worth using where buttons span most of the strip being scrolled (a category
     * tab row) — a small, isolated control (a quantity stepper, an Edit button) is left
     * as an ordinary precise tap target everywhere else in the app on purpose.
     */
    public static void installOnButton(AbstractButton button) {
        Drag drag = new Drag(button);
        button.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { drag.start(e); }
            @Override public void mouseReleased(MouseEvent e) { drag.release(); }
        });
        button.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                boolean wasDragging = drag.wasDrag();
                drag.drag(e);
                if (!wasDragging && drag.wasDrag()) {
                    button.getModel().setArmed(false);
                    button.getModel().setPressed(false);
                }
            }
        });
    }

    private static final class Drag {
        private final JComponent target;
        private Point last;
        private boolean dragging;
        private long lastEventTimeMs;
        /** Exponentially-smoothed instantaneous velocity in px/ms — smoothed so a single
         *  noisy or unusually large event (common right before release, e.g. the last
         *  sample before a finger lifts) doesn't singlehandedly set the flick speed. */
        private double velocityX;
        private double velocityY;
        private Timer momentumTimer;

        Drag(JComponent target) {
            this.target = target;
        }

        void start(MouseEvent e) {
            if (momentumTimer != null) {
                momentumTimer.stop();
                momentumTimer = null;
            }
            last = e.getPoint();
            lastEventTimeMs = System.currentTimeMillis();
            dragging = false;
            velocityX = 0;
            velocityY = 0;
        }

        boolean wasDrag() {
            return dragging;
        }

        void drag(MouseEvent e) {
            if (last == null) return;
            int dx = e.getX() - last.x;
            int dy = e.getY() - last.y;
            long now = System.currentTimeMillis();

            if (!dragging) {
                if (Math.hypot(dx, dy) < THRESHOLD_PX) return;
                // Just crossed the threshold: arm the drag but don't apply this event's
                // full pre-threshold distance as a single jump — panning starts cleanly
                // from THIS point on the next event instead, so the transition from "not
                // yet dragging" to "dragging" is a smooth continuation, not a sudden hop
                // by however far the finger travelled before crossing the threshold.
                dragging = true;
                last = e.getPoint();
                lastEventTimeMs = now;
                return;
            }

            applyDelta(dx, dy);

            long dt = Math.max(1, now - lastEventTimeMs);
            double instVX = dx / (double) dt;
            double instVY = dy / (double) dt;
            velocityX = velocityX * 0.5 + instVX * 0.5;
            velocityY = velocityY * 0.5 + instVY * 0.5;

            last = e.getPoint();
            lastEventTimeMs = now;
        }

        /** Kicks off the momentum glide if the finger was still moving fast enough at the
         *  moment of release for it to read as a deliberate flick rather than a drag that
         *  simply happened to stop. */
        void release() {
            if (!dragging) return;
            dragging = false;
            double vx = velocityX * TICK_MS;
            double vy = velocityY * TICK_MS;
            if (Math.hypot(vx, vy) < MIN_FLICK_PX_PER_TICK) return;
            startMomentum(vx, vy);
        }

        private void startMomentum(double vx, double vy) {
            double[] v = {vx, vy};
            momentumTimer = new Timer(TICK_MS, ev -> {
                boolean moved = applyDelta((int) Math.round(v[0]), (int) Math.round(v[1]));
                v[0] *= FRICTION;
                v[1] *= FRICTION;
                if (!moved || Math.hypot(v[0], v[1]) < MIN_VELOCITY_PX_PER_TICK) {
                    ((Timer) ev.getSource()).stop();
                    momentumTimer = null;
                }
            });
            momentumTimer.start();
        }

        /** Returns false when the viewport was already at a bound and genuinely didn't
         *  move — lets the momentum timer stop the instant a glide hits an edge, rather
         *  than ticking pointlessly until its velocity happens to decay to zero. */
        private boolean applyDelta(int dx, int dy) {
            if (dx == 0 && dy == 0) return true;
            JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, target);
            if (vp == null) return false;
            Dimension view = vp.getViewSize();
            Dimension extent = vp.getExtentSize();
            int maxX = Math.max(0, view.width - extent.width);
            int maxY = Math.max(0, view.height - extent.height);
            Point pos = vp.getViewPosition();
            int newX = clamp(pos.x - dx, 0, maxX);
            int newY = clamp(pos.y - dy, 0, maxY);
            if (newX == pos.x && newY == pos.y) return false;
            vp.setViewPosition(new Point(newX, newY));
            return true;
        }

        private static int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
