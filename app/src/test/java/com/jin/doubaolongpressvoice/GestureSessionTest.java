package com.jin.doubaolongpressvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GestureSessionTest {

    private static GestureSession session() {
        ZoneResolver.Geometry geometry = new ZoneResolver.Geometry(
                new ZoneResolver.Bounds(0, 400, 1080, 1000),
                new ZoneResolver.Bounds(0, 300, 1080, 400),
                new ZoneResolver.Bounds(0, 300, 1080, 1000));
        return new GestureSession(geometry, 4, 100L);
    }

    @Test
    public void onlyOneEventOwnerCanClaimSession() {
        GestureSession session = session();
        assertTrue(session.claim(GestureSession.Owner.UNCLAIMED,
                GestureSession.Owner.NATIVE_ASR_SURFACE));
        assertFalse(session.claim(GestureSession.Owner.UNCLAIMED,
                GestureSession.Owner.KEYBOARD_VIEW));
        assertEquals(GestureSession.Owner.NATIVE_ASR_SURFACE, session.owner());
    }

    @Test
    public void onlyOneTerminalActionCanWin() {
        GestureSession session = session();
        assertTrue(session.finish(GestureSession.Terminal.TOOLBAR_ACTION));
        assertFalse(session.finish(GestureSession.Terminal.COMMIT));
        assertFalse(session.finish(GestureSession.Terminal.CANCEL));
        assertEquals(GestureSession.Terminal.TOOLBAR_ACTION, session.terminal());
    }

    @Test
    public void pointAndZoneAreRecordedTogether() {
        GestureSession session = session();
        session.recordPoint(12.5f, 34.5f, ZoneResolver.Zone.OUTSIDE);
        assertEquals(12.5f, session.lastRawX, 0.0f);
        assertEquals(34.5f, session.lastRawY, 0.0f);
        assertEquals(ZoneResolver.Zone.OUTSIDE, session.currentZone);
    }
}
