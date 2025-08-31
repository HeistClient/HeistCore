// ============================================================================
// FILE: TreeDetector.java
// PACKAGE: ht.heist.corerl.object
// -----------------------------------------------------------------------------
// TITLE
//   TreeDetector — nearest-tree finder using ObjectComposition names.
//   Works across variants; avoids brittle ID matching.
// ============================================================================
package ht.heist.corerl.object;

import net.runelite.api.*;

public final class TreeDetector
{
    private TreeDetector() {}

    /** Find nearest matching tree to the player by **object name**, not ID. */
    public static GameObject findNearest(Client client, TreeType type)
    {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) return null;

        final Player me = client.getLocalPlayer();
        if (me == null) return null;

        final Scene scene = client.getScene();
        if (scene == null) return null;

        final Tile[][][] tiles = scene.getTiles();
        if (tiles == null) return null;

        GameObject best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int z = 0; z < tiles.length; z++)
        {
            final Tile[][] plane = tiles[z];
            if (plane == null) continue;

            for (int x = 0; x < plane.length; x++)
            {
                final Tile[] row = plane[x];
                if (row == null) continue;

                for (int y = 0; y < row.length; y++)
                {
                    final Tile t = row[y];
                    if (t == null) continue;

                    for (GameObject go : t.getGameObjects())
                    {
                        if (go == null) continue;
                        if (go.getConvexHull() == null) continue;

                        final ObjectComposition comp = client.getObjectDefinition(go.getId());
                        if (comp == null) continue;
                        final String name = safe(comp.getName());
                        if (!matches(type, name)) continue;

                        final int d = go.getWorldLocation().distanceTo(me.getWorldLocation());
                        if (d < bestDist)
                        {
                            best = go;
                            bestDist = d;
                        }
                    }
                }
            }
        }
        return best;
    }

    // ---- Matching -----------------------------------------------------------

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static boolean matches(TreeType type, String raw)
    {
        if (raw.isEmpty()) return false;

        // Normalize and exclude stumps
        final String n = raw.toLowerCase();
        if (n.contains("stump")) return false;

        switch (type)
        {
            case ANY:
                return isAnyTreeName(n);

            case NORMAL:
            case TREE:
                return eq(n,"tree") || eq(n,"evergreen") || eq(n,"dead tree");

            case DEAD_TREE:   return eq(n,"dead tree");
            case EVERGREEN:   return eq(n,"evergreen");

            // Add forgiving synonyms where regions may differ:
            case OAK:         return eq(n,"oak")         || eq(n,"oak tree");
            case WILLOW:      return eq(n,"willow")      || eq(n,"willow tree");
            case TEAK:        return eq(n,"teak")        || eq(n,"teak tree");
            case MAPLE_TREE:  return eq(n,"maple tree")  || eq(n,"maple");
            case MAHOGANY:    return eq(n,"mahogany")    || eq(n,"mahogany tree");
            case YEW:         return eq(n,"yew")         || eq(n,"yew tree");
            case MAGIC_TREE:  return eq(n,"magic tree")  || eq(n,"magic");
            case REDWOOD:     return eq(n,"redwood")     || eq(n,"redwood tree");
            case SULLIUSCEP:  return eq(n,"sulliuscep");
            default:          return false;
        }
    }

    private static boolean eq(String a, String b) { return a.equals(b); }

    private static boolean isAnyTreeName(String n)
    {
        if (n.contains("stump")) return false;
        return eq(n,"tree") || eq(n,"dead tree") || eq(n,"evergreen")
                || eq(n,"oak") || eq(n,"oak tree")
                || eq(n,"willow") || eq(n,"willow tree")
                || eq(n,"teak") || eq(n,"teak tree")
                || eq(n,"maple") || eq(n,"maple tree")
                || eq(n,"mahogany") || eq(n,"mahogany tree")
                || eq(n,"yew") || eq(n,"yew tree")
                || eq(n,"magic") || eq(n,"magic tree")
                || eq(n,"redwood") || eq(n,"redwood tree")
                || eq(n,"sulliuscep");
    }
}
