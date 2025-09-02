// ============================================================================
// FILE: TapResult.java
// MODULE: core-java (logging DTO)
// PACKAGE: ht.heist.corejava.logging
// -----------------------------------------------------------------------------
// TITLE
//   TapResult — Outcome record for a tap gesture.
//
// PURPOSE
//   Append-only JSONL line indicating whether the tap was accepted by the
//   client (processed == true) or was a "red click" miss (processed == false).
//
// DESIGN
//   • Keyed by 'event_id' so it correlates to the DOWN/UP TapEvent lines.
//   • Optional echo of menu context you matched against (helps audits).
// ============================================================================

package ht.heist.corejava.logging;

public final class TapResult
{
    public final String kind = "tap_result";
    public final String sessionId;
    public final String eventId;

    public final boolean processed;     // true if RL consumed it
    public final Long    tsMs;          // when you decided processed (epoch ms)

    // Optional echo (helpful for debugging)
    public final String  menuOption;
    public final String  menuTarget;
    public final Integer opcode;

    public TapResult(String sessionId, String eventId, boolean processed, Long tsMs,
                     String menuOption, String menuTarget, Integer opcode)
    {
        this.sessionId  = sessionId;
        this.eventId    = eventId;
        this.processed  = processed;
        this.tsMs       = tsMs;
        this.menuOption = menuOption;
        this.menuTarget = menuTarget;
        this.opcode     = opcode;
    }
}
