package com.jin.doubaolongpressvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ZoneResolverTest {

    private static final ZoneResolver.Geometry GEOMETRY = new ZoneResolver.Geometry(
            new ZoneResolver.Bounds(0, 400, 1080, 1000),
            new ZoneResolver.Bounds(0, 300, 1080, 400),
            new ZoneResolver.Bounds(0, 300, 1080, 1000));

    @Test
    public void keyboardPointResolvesToLetter() {
        assertEquals(ZoneResolver.Zone.LETTER,
                ZoneResolver.resolve(400, 700, GEOMETRY));
    }

    @Test
    public void toolbarPointResolvesToToolbar() {
        assertEquals(ZoneResolver.Zone.TOOLBAR,
                ZoneResolver.resolve(400, 350, GEOMETRY));
    }

    @Test
    public void pointOutsideInputViewResolvesToOutside() {
        assertEquals(ZoneResolver.Zone.OUTSIDE,
                ZoneResolver.resolve(400, 200, GEOMETRY));
    }

    @Test
    public void sharedEdgeUsesHalfOpenBounds() {
        assertEquals(ZoneResolver.Zone.LETTER,
                ZoneResolver.resolve(400, 400, GEOMETRY));
    }

    @Test
    public void toolbarWinsIfBoundsOverlap() {
        ZoneResolver.Geometry overlapping = new ZoneResolver.Geometry(
                new ZoneResolver.Bounds(0, 350, 1080, 1000),
                new ZoneResolver.Bounds(0, 300, 1080, 450),
                new ZoneResolver.Bounds(0, 300, 1080, 1000));
        assertEquals(ZoneResolver.Zone.TOOLBAR,
                ZoneResolver.resolve(400, 400, overlapping));
    }

    @Test
    public void unusableGeometryFailsLoudly() {
        ZoneResolver.Geometry unusable = new ZoneResolver.Geometry(
                new ZoneResolver.Bounds(0, 0, 0, 0), null, null);
        assertThrows(IllegalStateException.class,
                () -> ZoneResolver.resolve(0, 0, unusable));
    }
}
