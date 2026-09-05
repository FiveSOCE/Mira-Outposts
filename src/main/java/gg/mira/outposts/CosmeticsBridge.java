package gg.mira.outposts;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CosmeticsBridge {
    private CosmeticsBridge() { }

    public static void play(Player viewer, String eventId, Location location) {
        if (viewer == null || eventId == null || location == null) return;
        Plugin cosmetics = Bukkit.getPluginManager().getPlugin("MiraCosmetics");
        if (cosmetics == null || !cosmetics.isEnabled()) return;
        try {
            cosmetics.getClass().getMethod("playVisualEvent", Player.class, String.class, Location.class)
                    .invoke(cosmetics, viewer, eventId, location);
        } catch (ReflectiveOperationException ignored) { }
    }

    public static void playNearby(Location location, String eventId, double radius) {
        if (location == null || location.getWorld() == null) return;
        double radiusSquared = radius * radius;
        for (Player viewer : location.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(location) <= radiusSquared) {
                play(viewer, eventId, location);
            }
        }
    }
}
