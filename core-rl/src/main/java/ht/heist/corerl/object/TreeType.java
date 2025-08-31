// ============================================================================
// FILE: TreeType.java
// PACKAGE: ht.heist.corerl.object
// -----------------------------------------------------------------------------
// TITLE
//   TreeType — user-facing families for config dropdowns.
//
// NOTES
//   • NORMAL kept as alias for basic trees to cover older saved configs.
//   • ANY means any known tree name (excludes stumps).
// ============================================================================
package ht.heist.corerl.object;

public enum TreeType {
    ANY,
    NORMAL,           // alias for "Tree" family
    TREE, DEAD_TREE, EVERGREEN,
    OAK, WILLOW, TEAK, MAPLE_TREE, MAHOGANY,
    YEW, MAGIC_TREE, REDWOOD, SULLIUSCEP
}
