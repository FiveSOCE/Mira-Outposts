package gg.mira.outposts;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.factions.api.MiraFactionsApi;
import gg.mira.outposts.api.event.OutpostCapturedEvent;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

public final class MiraOutpostsPlugin extends JavaPlugin {
    public static final List<ChannelExample> CHANNELS = List.of(
            new ChannelExample("xp", Material.EXPERIENCE_BOTTLE, "XP multiplier", "Used by XP-aware Mira integrations."),
            new ChannelExample("mob_drops", Material.ROTTEN_FLESH, "Mob drops", "Multiplier channel for mob-drop rewards."),
            new ChannelExample("shop_sell", Material.EMERALD, "Shop sell value", "Used by shop/economy integrations."),
            new ChannelExample("crate_chance", Material.TRIPWIRE_HOOK, "Crate chance", "Multiplier channel for crate chance integrations."),
            new ChannelExample("spawner_rate", Material.SPAWNER, "Spawner production", "Used by MiraSpawners production.")
    );

    private final Map<String, Outpost> outposts = new LinkedHashMap<>();
    private final Map<String, Capture> captures = new HashMap<>();
    private final Map<String, BossBar> bossBars = new HashMap<>();

    private File file;
    private MiraCore core;
    private MiraFactionsApi factions;
    private OutpostsApi api;
    private Object boostersApi;
    private Method boostersMultiplierMethod;
    private long lastBoosterResolveAttempt;
    private FaweSelectionService fawe;
    private OutpostGuiService gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();

        RegisteredServiceProvider<MiraFactionsApi> factionsRegistration =
                getServer().getServicesManager().getRegistration(MiraFactionsApi.class);
        if (factionsRegistration == null || factionsRegistration.getProvider() == null) {
            throw new IllegalStateException("MiraFactions API is required for MiraOutposts.");
        }
        factions = factionsRegistration.getProvider();

        file = new File(getDataFolder(), "outposts.yml");
        load();

        fawe = new FaweSelectionService();
        gui = new OutpostGuiService(this, fawe);
        getServer().getPluginManager().registerEvents(new OutpostGuiListener(gui), this);

        api = new OutpostsApiImpl();
        getServer().getServicesManager().register(OutpostsApi.class, api, this, ServicePriority.Normal);
        core.services().register(OutpostsApi.class, api);
        core.modules().register(this, "MiraOutposts");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "FAWE regions, GUI administration, scheduled capture runs, boss bars and multiplier API ready");

        resolveBoostersApi();
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        getLogger().info("MiraOutposts v" + getPluginMeta().getVersion()
                + " enabled with " + outposts.size() + " outpost(s).");
    }

    @Override
    public void onDisable() {
        for (BossBar bar : bossBars.values()) bar.removeAll();
        bossBars.clear();
        save();
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (api != null) core.services().unregister(OutpostsApi.class, api);
            core.modules().unregister(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "&cMiraOutposts administration is player-GUI based.");
            return true;
        }
        if (!player.hasPermission("miraoutposts.use")) {
            msg(player, "&cYou do not have permission.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            gui.openMain(player, 0);
            return true;
        }

        if (args[0].equalsIgnoreCase("info") && args.length >= 2) {
            Outpost outpost = outposts.get(sanitizeId(args[1]));
            if (outpost == null) {
                msg(player, "&cOutpost not found.");
                return true;
            }
            gui.openEditor(player, outpost.id());
            return true;
        }

        if (args[0].equalsIgnoreCase("start") && args.length >= 2 && player.hasPermission("miraoutposts.admin")) {
            if (!startOutpost(sanitizeId(args[1]), false, player)) msg(player, "&cCould not start that outpost.");
            return true;
        }

        if (args[0].equalsIgnoreCase("stop") && args.length >= 2 && player.hasPermission("miraoutposts.admin")) {
            if (!stopOutpost(sanitizeId(args[1]), player, false)) msg(player, "&cThat outpost is not running.");
            return true;
        }

        gui.openMain(player, 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("gui", "info"));
            if (sender.hasPermission("miraoutposts.admin")) values.addAll(List.of("start", "stop"));
            return complete(args[0], values);
        }
        if (args.length == 2 && List.of("info", "start", "stop").contains(args[0].toLowerCase(Locale.ROOT))) {
            return complete(args[1], outposts.keySet());
        }
        return List.of();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        processSchedules(now);

        boolean resetEmpty = getConfig().getBoolean("capture.reset-when-empty", true);
        boolean resetContested = getConfig().getBoolean("capture.reset-when-contested", true);

        for (Outpost outpost : new ArrayList<>(outposts.values())) {
            if (!outpost.running()) {
                hideBossBar(outpost.id());
                continue;
            }

            World world = Bukkit.getWorld(outpost.world());
            if (world == null) {
                hideBossBar(outpost.id());
                continue;
            }

            List<Player> inside = world.getPlayers().stream()
                    .filter(player -> !player.isDead() && outpost.contains(player.getLocation()))
                    .toList();

            Map<UUID, String> factionsPresent = new LinkedHashMap<>();
            for (Player player : inside) {
                Optional<UUID> factionId = factions.factionId(player.getUniqueId());
                if (factionId.isEmpty()) continue;
                factionsPresent.putIfAbsent(factionId.get(),
                        factions.factionName(player.getUniqueId()).orElse(factionId.get().toString()));
            }

            Capture current = captures.get(outpost.id());
            BarState barState;

            if (factionsPresent.isEmpty()) {
                if (resetEmpty) captures.remove(outpost.id());
                barState = new BarState("UNCLAIMED", BarColor.WHITE, 0D);
            } else if (factionsPresent.size() > 1) {
                if (resetContested) captures.remove(outpost.id());
                barState = new BarState("CONTESTED", BarColor.RED, 1D);
            } else {
                UUID factionId = factionsPresent.keySet().iterator().next();
                String factionName = factionsPresent.get(factionId);

                if (factionId.equals(outpost.ownerId())) {
                    captures.remove(outpost.id());
                    barState = new BarState("CONTROLLED BY " + factionName, BarColor.GREEN, 1D);
                } else {
                    if (current == null || !current.factionId().equals(factionId)) {
                        current = new Capture(factionId, factionName, 0);
                    }
                    Capture progressed = new Capture(current.factionId(), current.factionName(), current.seconds() + 1);
                    captures.put(outpost.id(), progressed);
                    double progress = Math.min(1D, (double) progressed.seconds() / outpost.captureSeconds());
                    barState = new BarState(factionName + " CAPTURING "
                            + progressed.seconds() + "/" + outpost.captureSeconds() + "s",
                            BarColor.YELLOW, progress);

                    if (progressed.seconds() >= outpost.captureSeconds()) {
                        completeCapture(outpost, progressed);
                        Outpost updated = outposts.get(outpost.id());
                        barState = new BarState("CONTROLLED BY " + progressed.factionName(), BarColor.GREEN, 1D);
                        outpost = updated;
                    }
                }
            }

            updateBossBar(outpost, inside, barState);
        }
    }

    private void processSchedules(long now) {
        for (Outpost outpost : new ArrayList<>(outposts.values())) {
            if (outpost.running() && outpost.scheduledStopAt() > 0L && now >= outpost.scheduledStopAt()) {
                stopOutpost(outpost.id(), null, true);
                continue;
            }

            if (!outpost.running() && outpost.scheduleIntervalSeconds() > 0L
                    && outpost.nextStartAt() > 0L && now >= outpost.nextStartAt()) {
                startOutpost(outpost.id(), true, null);
            }
        }
    }

    boolean startOutpost(String id, boolean scheduled, Player actor) {
        Outpost current = outposts.get(id);
        if (current == null || current.running()) return false;

        long now = System.currentTimeMillis();
        long scheduledStop = scheduled && current.scheduledRunSeconds() > 0L
                ? now + current.scheduledRunSeconds() * 1000L : 0L;
        long next = current.scheduleIntervalSeconds() > 0L
                ? now + current.scheduleIntervalSeconds() * 1000L : 0L;

        Outpost updated = current.withRuntime(true, null, null, next, scheduledStop);
        outposts.put(id, updated);
        captures.remove(id);
        save();

        audit("OUTPOST_STARTED", actor, updated, Map.of(
                "scheduled", Boolean.toString(scheduled),
                "nextStartAt", Long.toString(next),
                "scheduledStopAt", Long.toString(scheduledStop)));
        broadcast("&6[Outpost] &e" + id + " &7is now active and ready to capture.");
        return true;
    }

    boolean stopOutpost(String id, Player actor, boolean scheduled) {
        Outpost current = outposts.get(id);
        if (current == null || !current.running()) return false;

        Outpost updated = current.withRuntime(false, current.ownerId(), current.ownerName(),
                current.nextStartAt(), 0L);
        outposts.put(id, updated);
        captures.remove(id);
        hideBossBar(id);
        save();

        audit("OUTPOST_STOPPED", actor, updated, Map.of("scheduled", Boolean.toString(scheduled)));
        broadcast("&6[Outpost] &e" + id + " &7has stopped.");
        return true;
    }

    void setSchedule(String id, long intervalSeconds, long runSeconds, Player actor) {
        Outpost current = outposts.get(id);
        if (current == null) return;
        long interval = Math.max(0L, intervalSeconds);
        long run = Math.max(60L, runSeconds);
        long next = interval <= 0L ? 0L : System.currentTimeMillis() + interval * 1000L;

        Outpost updated = current.withSchedule(interval, run, next);
        outposts.put(id, updated);
        save();
        audit("OUTPOST_SCHEDULE_CHANGED", actor, updated,
                Map.of("intervalSeconds", Long.toString(interval), "runSeconds", Long.toString(run)));
    }

    void updateCaptureSeconds(String id, int seconds, Player actor) {
        Outpost current = outposts.get(id);
        if (current == null) return;
        int safe = Math.max(5, Math.min(getConfig().getInt("creation.max-capture-seconds", 3600), seconds));
        outposts.put(id, current.withCaptureSeconds(safe));
        captures.remove(id);
        save();
        audit("OUTPOST_CAPTURE_TIME_CHANGED", actor, current, Map.of("seconds", Integer.toString(safe)));
    }

    void updateMultiplier(String id, double multiplier, Player actor) {
        Outpost current = outposts.get(id);
        if (current == null) return;
        double maximum = Math.max(1D, getConfig().getDouble("creation.max-multiplier", 100D));
        if (!Double.isFinite(multiplier) || multiplier < 1D || multiplier > maximum) return;
        outposts.put(id, current.withMultiplier(multiplier));
        save();
        audit("OUTPOST_MULTIPLIER_CHANGED", actor, current, Map.of("multiplier", Double.toString(multiplier)));
    }

    void updateChannel(String id, String channel, Player actor) {
        Outpost current = outposts.get(id);
        String safe = sanitizeChannel(channel);
        if (current == null || safe.isBlank()) return;
        outposts.put(id, current.withChannel(safe));
        save();
        audit("OUTPOST_CHANNEL_CHANGED", actor, current, Map.of("channel", safe));
    }

    boolean updateRegionFromFawe(String id, Player actor) {
        Outpost current = outposts.get(id);
        if (current == null) return false;
        FaweSelectionService.SelectionResult result = fawe.selection(actor);
        if (!result.success()) {
            msg(actor, "&c" + result.error());
            return false;
        }
        if (!validateSelection(actor, result.bounds())) return false;

        FaweSelectionService.SelectionBounds b = result.bounds();
        outposts.put(id, current.withBounds(b.world(), b.minX(), b.maxX(), b.minZ(), b.maxZ()));
        captures.remove(id);
        save();
        audit("OUTPOST_REGION_CHANGED", actor, current, Map.of(
                "world", b.world(), "minX", Integer.toString(b.minX()), "maxX", Integer.toString(b.maxX()),
                "minZ", Integer.toString(b.minZ()), "maxZ", Integer.toString(b.maxZ())));
        return true;
    }

    boolean createFromSelection(Player actor, String rawId) {
        String id = sanitizeId(rawId);
        if (id.isBlank() || outposts.containsKey(id)) {
            msg(actor, "&cThat outpost ID is invalid or already exists.");
            return false;
        }

        FaweSelectionService.SelectionResult result = fawe.selection(actor);
        if (!result.success()) {
            msg(actor, "&c" + result.error());
            return false;
        }
        if (!validateSelection(actor, result.bounds())) return false;

        FaweSelectionService.SelectionBounds b = result.bounds();
        Outpost outpost = new Outpost(id, b.world(), b.minX(), b.maxX(), b.minZ(), b.maxZ(),
                getConfig().getInt("defaults.capture-seconds", 30),
                getConfig().getString("defaults.channel", "spawner_rate"),
                getConfig().getDouble("defaults.multiplier", 1.25D),
                null, null, false, 0L,
                Math.max(60L, getConfig().getLong("schedule.default-run-minutes", 30L) * 60L),
                0L, 0L);
        outposts.put(id, outpost);
        save();
        audit("OUTPOST_CREATED", actor, outpost, Map.of("source", "FAWE_SELECTION", "area", Long.toString(b.area())));
        return true;
    }

    private boolean validateSelection(Player actor, FaweSelectionService.SelectionBounds b) {
        long maxArea = Math.max(64L, getConfig().getLong("creation.max-area-blocks", 262144L));
        int maxSide = Math.max(8, getConfig().getInt("creation.max-side-length", 512));
        if (b.area() > maxArea || b.width() > maxSide || b.depth() > maxSide) {
            msg(actor, "&cThat selection is too large. Max side " + maxSide + ", max area " + maxArea + " blocks.");
            return false;
        }

        World world = Bukkit.getWorld(b.world());
        if (world == null) {
            msg(actor, "&cThe selected world is not loaded.");
            return false;
        }

        int minChunkX = b.minX() >> 4;
        int maxChunkX = b.maxX() >> 4;
        int minChunkZ = b.minZ() >> 4;
        int maxChunkZ = b.maxZ() >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Location sample = new Location(world, (cx << 4) + 8, world.getSeaLevel(), (cz << 4) + 8);
                if (factions.isSafeZone(sample)) {
                    msg(actor, "&cThe FAWE selection overlaps a MiraFactions SafeZone.");
                    return false;
                }
            }
        }
        return true;
    }

    boolean removeOutpost(String id, Player actor) {
        Outpost removed = outposts.remove(id);
        captures.remove(id);
        hideBossBar(id);
        if (removed == null) return false;
        save();
        audit("OUTPOST_REMOVED", actor, removed, Map.of());
        return true;
    }

    private void completeCapture(Outpost previous, Capture capture) {
        Outpost captured = previous.withOwner(capture.factionId(), capture.factionName());
        outposts.put(previous.id(), captured);
        captures.remove(previous.id());
        save();

        Bukkit.getPluginManager().callEvent(new OutpostCapturedEvent(
                view(captured), previous.ownerId(), previous.ownerName()));

        audit("OUTPOST_CAPTURED", null, captured, Map.of(
                "factionId", capture.factionId().toString(),
                "factionName", capture.factionName(),
                "previousOwner", previous.ownerId() == null ? "unclaimed" : previous.ownerId().toString()));

        broadcast("&6[Outpost] &f" + capture.factionName()
                + " &7captured &e" + captured.id()
                + " &7and controls &f" + captured.channel()
                + " x" + format(captured.multiplier()) + "&7 while the outpost is active.");
    }

    private void updateBossBar(Outpost outpost, List<Player> inside, BarState state) {
        BossBar bar = bossBars.computeIfAbsent(outpost.id(),
                ignored -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));

        bar.setTitle(ChatColor.translateAlternateColorCodes('&',
                "&6&l" + outpost.id().toUpperCase(Locale.ROOT)
                        + " &8- &f" + state.label()
                        + " &8- &7" + outpost.channel() + " x" + format(outpost.multiplier())));
        bar.setColor(state.color());
        bar.setProgress(Math.max(0D, Math.min(1D, state.progress())));

        Set<UUID> wanted = inside.stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
        for (Player viewer : new ArrayList<>(bar.getPlayers())) {
            if (!wanted.contains(viewer.getUniqueId())) bar.removePlayer(viewer);
        }
        for (Player viewer : inside) {
            if (!bar.getPlayers().contains(viewer)) bar.addPlayer(viewer);
        }
        bar.setVisible(!inside.isEmpty());
    }

    private void hideBossBar(String id) {
        BossBar bar = bossBars.get(id);
        if (bar == null) return;
        bar.removeAll();
        bar.setVisible(false);
    }

    private double factionMultiplier(UUID factionId, String channel) {
        if (factionId == null || channel == null || channel.isBlank()) return 1D;
        double result = 1D;
        double maximum = Math.max(1D, getConfig().getDouble("api.max-effective-multiplier", 1000D));
        for (Outpost outpost : outposts.values()) {
            if (!outpost.running()) continue;
            if (!factionId.equals(outpost.ownerId()) || !outpost.channel().equalsIgnoreCase(channel)) continue;
            result = safeMultiply(result, outpost.multiplier(), maximum);
        }
        return result;
    }

    private double combinedPlayerMultiplier(UUID player, String channel) {
        double outpostMultiplier = factions.factionId(player)
                .map(factionId -> factionMultiplier(factionId, channel)).orElse(1D);
        if (!getConfig().getBoolean("boosters.combine-in-player-multiplier", true)) return outpostMultiplier;
        double booster = boosterMultiplier(channel, player);
        return safeMultiply(outpostMultiplier, booster,
                Math.max(1D, getConfig().getDouble("api.max-effective-multiplier", 1000D)));
    }

    private double boosterMultiplier(String channel, UUID player) {
        if (boostersApi == null && System.currentTimeMillis() - lastBoosterResolveAttempt > 30_000L) resolveBoostersApi();
        if (boostersApi == null || boostersMultiplierMethod == null) return 1D;
        try {
            Object value = boostersMultiplierMethod.invoke(boostersApi, channel, player);
            if (!(value instanceof Number number)) return 1D;
            double multiplier = number.doubleValue();
            return Double.isFinite(multiplier) && multiplier > 0D ? multiplier : 1D;
        } catch (ReflectiveOperationException exception) {
            boostersApi = null;
            boostersMultiplierMethod = null;
            return 1D;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void resolveBoostersApi() {
        lastBoosterResolveAttempt = System.currentTimeMillis();
        try {
            Class<?> type = Class.forName("com.mira.boosters.api.MiraBoostersApi");
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration((Class) type);
            if (registration == null || registration.getProvider() == null) return;
            boostersApi = registration.getProvider();
            boostersMultiplierMethod = type.getMethod("multiplier", String.class, UUID.class);
        } catch (ReflectiveOperationException ignored) {
            boostersApi = null;
            boostersMultiplierMethod = null;
        }
    }

    public interface OutpostsApi {
        Collection<OutpostView> outposts();
        Optional<OutpostView> outpost(String id);
        Optional<CaptureView> capture(String id);
        double multiplier(UUID factionId, String channel);
        double playerMultiplier(UUID player, String channel);
        List<OutpostView> heldBy(UUID factionId);
        boolean running(String id);
    }

    public record OutpostView(String id, String world, int minX, int maxX, int minZ, int maxZ,
                              int captureSeconds, String channel, double multiplier,
                              UUID ownerId, String ownerName, boolean running,
                              long scheduleIntervalSeconds, long scheduledRunSeconds, long nextStartAt) {
        public boolean contains(Location location) {
            return location != null && location.getWorld() != null
                    && location.getWorld().getName().equals(world)
                    && location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }
    }

    public record CaptureView(String outpostId, UUID factionId, String factionName,
                              int seconds, int requiredSeconds) {
        public double progress() {
            return requiredSeconds <= 0 ? 0D : Math.min(1D, (double) seconds / requiredSeconds);
        }
    }

    private final class OutpostsApiImpl implements OutpostsApi {
        @Override public Collection<OutpostView> outposts() { return outposts.values().stream().map(MiraOutpostsPlugin::view).toList(); }
        @Override public Optional<OutpostView> outpost(String id) { return Optional.ofNullable(outposts.get(sanitizeId(id))).map(MiraOutpostsPlugin::view); }
        @Override public Optional<CaptureView> capture(String id) {
            String normalized = sanitizeId(id);
            Outpost outpost = outposts.get(normalized);
            Capture capture = captures.get(normalized);
            if (outpost == null || capture == null) return Optional.empty();
            return Optional.of(new CaptureView(normalized, capture.factionId(), capture.factionName(), capture.seconds(), outpost.captureSeconds()));
        }
        @Override public double multiplier(UUID factionId, String channel) { return factionMultiplier(factionId, channel); }
        @Override public double playerMultiplier(UUID player, String channel) { return combinedPlayerMultiplier(player, channel); }
        @Override public List<OutpostView> heldBy(UUID factionId) {
            return outposts.values().stream().filter(o -> factionId != null && factionId.equals(o.ownerId()))
                    .map(MiraOutpostsPlugin::view).toList();
        }
        @Override public boolean running(String id) {
            Outpost outpost = outposts.get(sanitizeId(id));
            return outpost != null && outpost.running();
        }
    }

    Collection<Outpost> outpostsInternal() { return Collections.unmodifiableCollection(outposts.values()); }
    Outpost outpostInternal(String id) { return outposts.get(sanitizeId(id)); }
    Capture captureInternal(String id) { return captures.get(sanitizeId(id)); }
    long defaultRunSeconds() { return Math.max(60L, getConfig().getLong("schedule.default-run-minutes", 30L) * 60L); }
    int maxCaptureSeconds() { return Math.max(5, getConfig().getInt("creation.max-capture-seconds", 3600)); }
    double maxMultiplier() { return Math.max(1D, getConfig().getDouble("creation.max-multiplier", 100D)); }

    private void audit(String action, Player actor, Outpost outpost, Map<String, String> extra) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("world", outpost.world());
        metadata.put("minX", Integer.toString(outpost.minX()));
        metadata.put("maxX", Integer.toString(outpost.maxX()));
        metadata.put("minZ", Integer.toString(outpost.minZ()));
        metadata.put("maxZ", Integer.toString(outpost.maxZ()));
        metadata.put("channel", outpost.channel());
        metadata.put("multiplier", Double.toString(outpost.multiplier()));
        metadata.putAll(extra);
        core.audit().record("MiraOutposts", action,
                actor == null ? null : actor.getUniqueId(),
                actor == null ? "scheduler" : actor.getName(),
                outpost.id(), action.replace('_', ' '), Map.copyOf(metadata));
    }

    void msg(CommandSender sender, String raw) { core.messages().send(sender, raw); }

    private void broadcast(String raw) {
        for (Player player : Bukkit.getOnlinePlayers()) core.messages().send(player, raw);
        core.messages().send(Bukkit.getConsoleSender(), raw);
    }

    private static OutpostView view(Outpost o) {
        return new OutpostView(o.id(), o.world(), o.minX(), o.maxX(), o.minZ(), o.maxZ(),
                o.captureSeconds(), o.channel(), o.multiplier(), o.ownerId(), o.ownerName(),
                o.running(), o.scheduleIntervalSeconds(), o.scheduledRunSeconds(), o.nextStartAt());
    }

    static String sanitizeId(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_")
                .replaceAll("_+", "_").replaceAll("^_+|_+$", "");
    }
    static String sanitizeChannel(String input) { return sanitizeId(input); }
    static String format(double value) { return String.format(Locale.US, "%.2f", value); }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }

    private static double safeMultiply(double current, double adding, double maximum) {
        if (!Double.isFinite(current) || current <= 0D || !Double.isFinite(adding) || adding <= 0D) return 1D;
        double result = current * adding;
        if (!Double.isFinite(result)) return maximum;
        return Math.min(maximum, result);
    }

    private void load() {
        getDataFolder().mkdirs();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var root = yaml.getConfigurationSection("outposts");
        if (root == null) return;

        for (String rawId : root.getKeys(false)) {
            try {
                String id = sanitizeId(rawId);
                if (id.isBlank()) continue;
                String base = rawId + ".";
                String world = root.getString(base + "world");
                if (world == null || world.isBlank()) continue;

                int minX, maxX, minZ, maxZ;
                if (root.contains(base + "min-x")) {
                    minX = root.getInt(base + "min-x");
                    maxX = root.getInt(base + "max-x");
                    minZ = root.getInt(base + "min-z");
                    maxZ = root.getInt(base + "max-z");
                } else {
                    double x = root.getDouble(base + "x");
                    double z = root.getDouble(base + "z");
                    int radius = Math.max(1, root.getInt(base + "radius", 8));
                    minX = (int) Math.floor(x) - radius;
                    maxX = (int) Math.floor(x) + radius;
                    minZ = (int) Math.floor(z) - radius;
                    maxZ = (int) Math.floor(z) + radius;
                }

                int captureSeconds = Math.max(5, root.getInt(base + "capture-seconds", 30));
                String channel = sanitizeChannel(root.getString(base + "channel", "spawner_rate"));
                if (channel.isBlank()) channel = "spawner_rate";
                double multiplier = root.getDouble(base + "multiplier", 1.25D);
                if (!Double.isFinite(multiplier) || multiplier < 1D) multiplier = 1D;

                String ownerText = root.getString(base + "owner-id");
                UUID ownerId = ownerText == null || ownerText.isBlank() ? null : UUID.fromString(ownerText);

                outposts.put(id, new Outpost(id, world, minX, maxX, minZ, maxZ,
                        captureSeconds, channel, multiplier, ownerId, root.getString(base + "owner-name"),
                        root.getBoolean(base + "running", false),
                        Math.max(0L, root.getLong(base + "schedule-interval-seconds", 0L)),
                        Math.max(60L, root.getLong(base + "scheduled-run-seconds", defaultRunSeconds())),
                        Math.max(0L, root.getLong(base + "next-start-at", 0L)),
                        Math.max(0L, root.getLong(base + "scheduled-stop-at", 0L))));
            } catch (RuntimeException exception) {
                getLogger().warning("Skipping invalid outpost " + rawId + ": " + exception.getMessage());
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Outpost o : outposts.values()) {
            String base = "outposts." + o.id() + ".";
            yaml.set(base + "world", o.world());
            yaml.set(base + "min-x", o.minX());
            yaml.set(base + "max-x", o.maxX());
            yaml.set(base + "min-z", o.minZ());
            yaml.set(base + "max-z", o.maxZ());
            yaml.set(base + "capture-seconds", o.captureSeconds());
            yaml.set(base + "channel", o.channel());
            yaml.set(base + "multiplier", o.multiplier());
            yaml.set(base + "owner-id", o.ownerId() == null ? null : o.ownerId().toString());
            yaml.set(base + "owner-name", o.ownerName());
            yaml.set(base + "running", o.running());
            yaml.set(base + "schedule-interval-seconds", o.scheduleIntervalSeconds());
            yaml.set(base + "scheduled-run-seconds", o.scheduledRunSeconds());
            yaml.set(base + "next-start-at", o.nextStartAt());
            yaml.set(base + "scheduled-stop-at", o.scheduledStopAt());
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            getLogger().severe("Could not save outposts.yml: " + exception.getMessage());
        }
    }

    public record ChannelExample(String id, Material icon, String name, String description) { }

    record Outpost(String id, String world, int minX, int maxX, int minZ, int maxZ,
                   int captureSeconds, String channel, double multiplier,
                   UUID ownerId, String ownerName, boolean running,
                   long scheduleIntervalSeconds, long scheduledRunSeconds,
                   long nextStartAt, long scheduledStopAt) {
        boolean contains(Location location) {
            return location != null && location.getWorld() != null
                    && location.getWorld().getName().equals(world)
                    && location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }
        int width() { return maxX - minX + 1; }
        int depth() { return maxZ - minZ + 1; }
        long area() { return (long) width() * depth(); }
        Outpost withOwner(UUID id, String name) { return new Outpost(this.id, world,minX,maxX,minZ,maxZ,captureSeconds,channel,multiplier,id,name,running,scheduleIntervalSeconds,scheduledRunSeconds,nextStartAt,scheduledStopAt); }
        Outpost withRuntime(boolean running, UUID ownerId, String ownerName, long next, long stop) { return new Outpost(id,world,minX,maxX,minZ,maxZ,captureSeconds,channel,multiplier,ownerId,ownerName,running,scheduleIntervalSeconds,scheduledRunSeconds,next,stop); }
        Outpost withSchedule(long interval,long run,long next) { return new Outpost(id,world,minX,maxX,minZ,maxZ,captureSeconds,channel,multiplier,ownerId,ownerName,running,interval,run,next,scheduledStopAt); }
        Outpost withCaptureSeconds(int seconds) { return new Outpost(id,world,minX,maxX,minZ,maxZ,seconds,channel,multiplier,ownerId,ownerName,running,scheduleIntervalSeconds,scheduledRunSeconds,nextStartAt,scheduledStopAt); }
        Outpost withMultiplier(double value) { return new Outpost(id,world,minX,maxX,minZ,maxZ,captureSeconds,channel,value,ownerId,ownerName,running,scheduleIntervalSeconds,scheduledRunSeconds,nextStartAt,scheduledStopAt); }
        Outpost withChannel(String value) { return new Outpost(id,world,minX,maxX,minZ,maxZ,captureSeconds,value,multiplier,ownerId,ownerName,running,scheduleIntervalSeconds,scheduledRunSeconds,nextStartAt,scheduledStopAt); }
        Outpost withBounds(String world,int minX,int maxX,int minZ,int maxZ) { return new Outpost(id,world,minX,maxX,minZ,maxZ,captureSeconds,channel,multiplier,ownerId,ownerName,running,scheduleIntervalSeconds,scheduledRunSeconds,nextStartAt,scheduledStopAt); }
    }

    record Capture(UUID factionId, String factionName, int seconds) { }
    private record BarState(String label, BarColor color, double progress) { }
}
