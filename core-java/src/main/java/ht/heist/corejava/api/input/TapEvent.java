// ============================================================================
// FILE: TapEvent.java
// MODULE: core-java (API / DTO)
// PACKAGE: ht.heist.corejava.api.input
// -----------------------------------------------------------------------------
// TITLE
//   TapEvent — Immutable cross-module DTO for mouse tap signals.
//
// PURPOSE
//   A single, immutable "tap" envelope used across modules (HUD, core-rl,
//   analyzers, loggers). It covers both manual (human) and synthetic taps,
//   and can include optional context when available.
//
// WHAT'S NEW (Phase A):
//   • Added optional 'eventId' (String) to correlate DOWN/UP + extra records
//     like 'move_features' and 'tap_result' for the *same* gesture.
//
// DESIGN
//   • Pure Java (no RuneLite imports) so every module can depend on it.
//   • All fields are finals; null = "unknown/not supplied".
//   • Lightweight enums for Type and Button to avoid platform coupling.
//   • Two constructors:
//       - legacy (without eventId) for existing call-sites
//       - full (with eventId) used by new HUD wiring
// ============================================================================

package ht.heist.corejava.api.input;

public final class TapEvent
{
    // ---- Basic time & canvas position --------------------------------------
    public final long   tsMs;        // when the tap signal was emitted (epoch ms)
    public final int    xCanvas;     // canvas-space X (pixels)
    public final int    yCanvas;     // canvas-space Y (pixels)

    // ---- Semantics ----------------------------------------------------------
    public enum Type   { DOWN, UP, CLICK }
    public enum Button { LEFT, RIGHT, MIDDLE, UNKNOWN }

    public final Type   type;
    public final Button button;

    // ---- Modifiers ----------------------------------------------------------
    public final boolean shift;
    public final boolean ctrl;
    public final boolean alt;

    // ---- Session / correlation ---------------------------------------------
    public final String sessionId;   // short id for current run/session
    public final String eventId;     // OPTIONAL: correlate DOWN/UP/features/results

    // ---- Optional context (extend as needed) -------------------------------
    public final Integer worldX;      // optional: world coords (tile-based)
    public final Integer worldY;      // optional
    public final Integer plane;       // optional: 0..3
    public final Integer sceneX;      // optional: scene/local coords
    public final Integer sceneY;      // optional
    public final Integer widgetId;    // optional: clicked widget id
    public final String  menuOption;  // optional: "Walk here", "Wield", etc.
    public final String  menuTarget;  // optional: "Tree", "Yew longbow", etc.
    public final Integer opcode;      // optional: RL menu opcode
    public final Boolean processed;   // optional: whether the game consumed it (red click = false)

    // ---- Legacy constructor (no eventId) -----------------------------------
    public TapEvent(
            long tsMs, int xCanvas, int yCanvas,
            Type type, Button button,
            boolean shift, boolean ctrl, boolean alt,
            String sessionId,
            Integer worldX, Integer worldY, Integer plane,
            Integer sceneX, Integer sceneY,
            Integer widgetId,
            String menuOption, String menuTarget,
            Integer opcode,
            Boolean processed)
    {
        this(tsMs, xCanvas, yCanvas, type, button, shift, ctrl, alt, sessionId,
                /*eventId*/ null,
                worldX, worldY, plane, sceneX, sceneY, widgetId, menuOption, menuTarget, opcode, processed);
    }

    // ---- Full constructor (with eventId) -----------------------------------
    public TapEvent(
            long tsMs, int xCanvas, int yCanvas,
            Type type, Button button,
            boolean shift, boolean ctrl, boolean alt,
            String sessionId,
            String eventId,
            Integer worldX, Integer worldY, Integer plane,
            Integer sceneX, Integer sceneY,
            Integer widgetId,
            String menuOption, String menuTarget,
            Integer opcode,
            Boolean processed)
    {
        this.tsMs      = tsMs;
        this.xCanvas   = xCanvas;
        this.yCanvas   = yCanvas;
        this.type      = type;
        this.button    = button;
        this.shift     = shift;
        this.ctrl      = ctrl;
        this.alt       = alt;
        this.sessionId = sessionId;
        this.eventId   = eventId;

        this.worldX     = worldX;
        this.worldY     = worldY;
        this.plane      = plane;
        this.sceneX     = sceneX;
        this.sceneY     = sceneY;
        this.widgetId   = widgetId;
        this.menuOption = menuOption;
        this.menuTarget = menuTarget;
        this.opcode     = opcode;
        this.processed  = processed;
    }
}
