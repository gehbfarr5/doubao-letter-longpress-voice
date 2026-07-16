package com.jin.doubaolongpressvoice;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One long-press gesture with one event owner and exactly one terminal action. */
final class GestureSession {

    enum Owner { UNCLAIMED, NATIVE_ASR_SURFACE, KEYBOARD_VIEW }
    enum Terminal { ACTIVE, COMMIT, CANCEL, TOOLBAR_ACTION, ABORTED }

    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    final long id = NEXT_ID.getAndIncrement();
    final ZoneResolver.Geometry geometry;
    final int enterOrdinal;
    final long startedElapsedMs;

    private final AtomicReference<Owner> owner = new AtomicReference<>(Owner.UNCLAIMED);
    private final AtomicReference<Terminal> terminal = new AtomicReference<>(Terminal.ACTIVE);

    volatile ZoneResolver.Zone currentZone = ZoneResolver.Zone.LETTER;
    volatile float lastRawX = Float.NaN;
    volatile float lastRawY = Float.NaN;

    GestureSession(ZoneResolver.Geometry geometry, int enterOrdinal, long startedElapsedMs) {
        this.geometry = geometry;
        this.enterOrdinal = enterOrdinal;
        this.startedElapsedMs = startedElapsedMs;
    }

    boolean claim(Owner expected, Owner next) {
        return owner.compareAndSet(expected, next);
    }

    Owner owner() {
        return owner.get();
    }

    boolean isActive() {
        return terminal.get() == Terminal.ACTIVE;
    }

    boolean finish(Terminal action) {
        if (action == Terminal.ACTIVE) {
            throw new IllegalArgumentException("terminal action must not be ACTIVE");
        }
        return terminal.compareAndSet(Terminal.ACTIVE, action);
    }

    Terminal terminal() {
        return terminal.get();
    }

    void recordPoint(float rawX, float rawY, ZoneResolver.Zone zone) {
        this.lastRawX = rawX;
        this.lastRawY = rawY;
        this.currentZone = zone;
    }
}
