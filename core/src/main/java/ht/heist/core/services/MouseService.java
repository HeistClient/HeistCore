package ht.heist.core.services;

import com.google.inject.ImplementedBy;
import ht.heist.core.impl.MouseServiceImpl;

import java.awt.Shape;
import java.awt.Rectangle;
import java.awt.Point;

@ImplementedBy(MouseServiceImpl.class)
public interface MouseService {
    /** Move + click human-like inside a Shape. If shift is true, holds Shift while clicking. */
    void humanClick(Shape targetShape, boolean shift);

    /** Move the mouse to a specific point on the game canvas (screen coordinates). */
    void moveMouseTo(Point p);

    /** Click at a specific point. */
    void clickAt(Point p, boolean shift);

    /** Convenience for inventory/minimap: click inside a rect bounds. */
    void humanClick(Rectangle bounds, boolean shift);
}
