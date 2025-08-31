// ============================================================================
// FILE: TimingPolicy.java
// PACKAGE: ht.heist.corejava.api.input
// TITLE: TimingPolicy — contract for human-like timing values
//
// WHAT THIS INTERFACE IS
//   • Source of durations used during the click sequence to avoid "yellow clicks":
//       (1) dwell before pressing (hover highlight time)
//       (2) reaction pause (human latency)
//       (3) button hold duration
//       (4) settle after release
//       (5) lead/lag around SHIFT key press/release
//
// WHY INTERFACE
//   • Lets you swap timing strategies (defaults, learned, HUD-configurable)
//     without changing any plugin code.
//
// NOTE: PURE JAVA.
// ============================================================================
package ht.heist.corejava.api.input;

public interface TimingPolicy {
    long dwellMs();     // hover (pre-press), prevents too-early press
    long reactionMs();  // small pause after hover before pressing
    long holdMs();      // BUTTON1 down duration
    long settleMs();    // tiny post-release pause
    long keyLeadMs();   // delay after KeyPressed(Shift), before MousePressed
    long keyLagMs();    // delay after MouseReleased, before KeyReleased(Shift)
}
