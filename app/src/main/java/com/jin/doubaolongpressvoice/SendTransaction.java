package com.jin.doubaolongpressvoice;

/** One user gesture, one target editor, at most one click. No Android dependencies. */
final class SendTransaction {
    enum State { PREPARED, READY, ATTEMPTED, OBSERVED_CLEARED, ABORTED }
    final long id, startedAt;
    final String packageName;
    final int windowId;
    private State state = State.PREPARED;
    private String expectedText;
    private long readyAt;

    SendTransaction(long id, String pkg, int windowId, long now) {
        this.id = id; this.packageName = pkg; this.windowId = windowId; this.startedAt = now;
    }
    State state() { return state; }
    boolean ready(long id, String text, long now) {
        if (this.id != id || state != State.PREPARED || text == null || text.trim().isEmpty()) return false;
        expectedText = text; readyAt = now; state = State.READY; return true;
    }
    boolean claimClick(String pkg, int window, boolean sameEditor, String text,
                       int candidateCount, boolean enabled, long now) {
        if (state != State.READY) return false;
        if (!packageName.equals(pkg) || windowId != window || !sameEditor || expired(now)) {
            abort(); return false;
        }
        if (candidateCount != 1 || !enabled || !expectedText.equals(text)) return false;
        state = State.ATTEMPTED;
        return true;
    }
    boolean observeCleared(boolean sameEditor, String text) {
        if (state != State.ATTEMPTED || !sameEditor || text == null || !text.isEmpty()) return false;
        state = State.OBSERVED_CLEARED; expectedText = null; return true;
    }
    boolean expired(long now) {
        return now - startedAt >= 30_000L || (state == State.READY && now - readyAt >= 2_000L);
    }
    void abort() { state = State.ABORTED; expectedText = null; }
}
