// ============================================================================
// FILE: ActionContext.java
// PACKAGE: ht.heist.corejava.api.input
// TITLE: ActionContext — tiny metadata for a click request (PURE Java)
//
// WHAT THIS CLASS IS
//   • A minimal container for flags that describe "how" a mouse click should
//     be executed (e.g., whether to hold SHIFT).
//
// WHY THIS EXISTS
//   • We want plugins to pass all “request details” through a single object
//     so the mouse executor (core) can keep logic centralized.
//
// FIELDS
//   • holdShift: if true → press SHIFT before mouse press, and release after.
//   • tag:       arbitrary label for logs/diagnostics ("woodcutter-chop").
//
// NOTE: PURE JAVA (no RuneLite imports).
// ============================================================================
package ht.heist.corejava.input;

public final class ActionContext {
    /** If true, we press SHIFT just before mouse press, and release after. */
    public final boolean holdShift;

    /** Optional label for logging/tracing ("woodcutter-chop", etc.). */
    public final String tag;

    public ActionContext(boolean holdShift, String tag) {
        this.holdShift = holdShift;
        this.tag = (tag == null) ? "" : tag;
    }
}
