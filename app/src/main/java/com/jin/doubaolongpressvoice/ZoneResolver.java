package com.jin.doubaolongpressvoice;

/** Pure geometry for classifying a gesture point in screen coordinates. */
final class ZoneResolver {

    enum Zone { LETTER, TOOLBAR, OUTSIDE }

    static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean isValid() {
            return right > left && bottom > top;
        }

        boolean contains(float x, float y) {
            return isValid() && x >= left && x < right && y >= top && y < bottom;
        }

        int width() {
            return Math.max(0, right - left);
        }

        int height() {
            return Math.max(0, bottom - top);
        }

        @Override
        public String toString() {
            return "[" + left + "," + top + "-" + right + "," + bottom + "]";
        }
    }

    static final class Geometry {
        final Bounds keyboard;
        final Bounds toolbar;
        final Bounds inputView;

        Geometry(Bounds keyboard, Bounds toolbar, Bounds inputView) {
            this.keyboard = keyboard;
            this.toolbar = toolbar;
            this.inputView = inputView;
        }

        boolean isUsable() {
            return keyboard != null && keyboard.isValid()
                    && toolbar != null && toolbar.isValid()
                    && inputView != null && inputView.isValid();
        }

        @Override
        public String toString() {
            return "keyboard=" + keyboard + " toolbar=" + toolbar + " input=" + inputView;
        }
    }

    private ZoneResolver() {
    }

    static Zone resolve(float rawX, float rawY, Geometry geometry) {
        if (geometry == null || !geometry.isUsable()) {
            throw new IllegalStateException("global geometry is unavailable");
        }
        if (geometry.toolbar.contains(rawX, rawY)) {
            return Zone.TOOLBAR;
        }
        if (geometry.keyboard.contains(rawX, rawY)) {
            return Zone.LETTER;
        }
        return Zone.OUTSIDE;
    }
}
