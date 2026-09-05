package gg.mira.outposts;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

final class OutpostRotationService {
    private final MiraOutpostsPlugin plugin;
    private final File stateFile;
    private final YamlConfiguration state;
    private int cursor;
    private long nextRotationAt;

    OutpostRotationService(MiraOutpostsPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "rotation-state.yml");
        this.state = YamlConfiguration.loadConfiguration(stateFile);
        this.cursor = Math.max(0, state.getInt("cursor", 0));
        this.nextRotationAt = Math.max(0L, state.getLong("next-rotation-at", 0L));
        if (enabled() && nextRotationAt <= 0L) scheduleNext(System.currentTimeMillis());
    }

    void tick(long now) {
        if (!enabled()) return;
        if (nextRotationAt <= 0L) scheduleNext(now);
        if (now >= nextRotationAt) rotate(null);
    }

    boolean controls(String outpostId) {
        if (!enabled() || outpostId == null) return false;
        return configured().stream().anyMatch(id -> id.equalsIgnoreCase(outpostId));
    }

    boolean rotate(Player actor) {
        if (!enabled()) {
            if (actor != null) plugin.msg(actor, "&cOutpost rotation is disabled in config.yml.");
            return false;
        }

        List<String> ids = configured().stream()
                .filter(id -> plugin.outpostInternal(id) != null)
                .toList();
        if (ids.isEmpty()) {
            if (actor != null) plugin.msg(actor, "&cNo valid outposts are configured for rotation.");
            scheduleNext(System.currentTimeMillis());
            return false;
        }

        for (String id : ids) {
            var outpost = plugin.outpostInternal(id);
            if (outpost != null && outpost.running()) plugin.stopOutpost(id, actor, true);
        }

        int activeCount = Math.max(1, Math.min(ids.size(), plugin.getConfig().getInt("rotation.active-count", 1)));
        boolean random = plugin.getConfig().getBoolean("rotation.random-order", false);
        List<String> selected = new ArrayList<>();

        if (random) {
            List<String> shuffled = new ArrayList<>(ids);
            Collections.shuffle(shuffled);
            selected.addAll(shuffled.subList(0, activeCount));
        } else {
            cursor %= ids.size();
            for (int i = 0; i < activeCount; i++) selected.add(ids.get((cursor + i) % ids.size()));
            cursor = (cursor + activeCount) % ids.size();
        }

        for (String id : selected) plugin.startOutpost(id, false, actor);
        scheduleNext(System.currentTimeMillis());
        save();

        plugin.broadcast("&dOutpost rotation changed. &7Active: &f" + String.join(", ", selected));
        plugin.auditRotation(actor, selected, nextRotationAt);
        return true;
    }

    long nextRotationAt() { return nextRotationAt; }

    List<String> configured() {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : plugin.getConfig().getStringList("rotation.outposts")) {
            String id = MiraOutpostsPlugin.sanitizeId(raw);
            if (!id.isBlank()) unique.add(id);
        }
        return List.copyOf(unique);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("rotation.enabled", false);
    }

    private void scheduleNext(long now) {
        long intervalMinutes = Math.max(1L, plugin.getConfig().getLong("rotation.interval-minutes", 180L));
        nextRotationAt = now + intervalMinutes * 60_000L;
        save();
    }

    void save() {
        state.set("cursor", cursor);
        state.set("next-rotation-at", nextRotationAt);
        try {
            plugin.getDataFolder().mkdirs();
            state.save(stateFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save rotation-state.yml: " + ex.getMessage());
        }
    }
}
