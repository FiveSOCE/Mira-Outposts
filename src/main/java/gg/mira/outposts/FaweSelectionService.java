package gg.mira.outposts;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Bukkit;

public final class FaweSelectionService {
    public SelectionResult selection(org.bukkit.entity.Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")) {
            return SelectionResult.error("FastAsyncWorldEdit is not installed or enabled.");
        }

        Player actor = BukkitAdapter.adapt(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
        com.sk89q.worldedit.world.World selectionWorld = session.getSelectionWorld();
        if (selectionWorld == null) {
            return SelectionResult.error("Make a FAWE cuboid selection first with //pos1 and //pos2.");
        }

        final Region region;
        try {
            region = session.getSelection(selectionWorld);
        } catch (IncompleteRegionException exception) {
            return SelectionResult.error("Your FAWE selection is incomplete. Set both //pos1 and //pos2.");
        }

        if (!(region instanceof CuboidRegion)) {
            return SelectionResult.error("MiraOutposts requires a cuboid FAWE selection made from two positions.");
        }

        org.bukkit.World world = BukkitAdapter.adapt(selectionWorld);
        if (world == null) {
            return SelectionResult.error("The FAWE selection world is not loaded.");
        }

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        return SelectionResult.success(new SelectionBounds(
                world.getName(),
                Math.min(min.x(), max.x()),
                Math.max(min.x(), max.x()),
                Math.min(min.z(), max.z()),
                Math.max(min.z(), max.z())
        ));
    }

    public record SelectionBounds(String world, int minX, int maxX, int minZ, int maxZ) {
        public int width() { return maxX - minX + 1; }
        public int depth() { return maxZ - minZ + 1; }
        public long area() { return (long) width() * depth(); }
        public int centerX() { return minX + (maxX - minX) / 2; }
        public int centerZ() { return minZ + (maxZ - minZ) / 2; }
    }

    public record SelectionResult(SelectionBounds bounds, String error) {
        public static SelectionResult success(SelectionBounds bounds) {
            return new SelectionResult(bounds, null);
        }

        public static SelectionResult error(String error) {
            return new SelectionResult(null, error);
        }

        public boolean success() {
            return bounds != null;
        }
    }
}
