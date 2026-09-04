package gg.mira.outposts;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.factions.api.MiraFactionsApi;
import gg.mira.outposts.api.event.OutpostCapturedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

public final class MiraOutpostsPlugin extends JavaPlugin {
    private final Map<String, Outpost> outposts = new LinkedHashMap<>();
    private final Map<String, Capture> captures = new HashMap<>();

    private File file;
    private MiraCore core;
    private MiraFactionsApi factions;
    private OutpostsApi api;
    private Object boostersApi;
    private Method boostersMultiplierMethod;
    private long lastBoosterResolveAttempt;

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

        api = new OutpostsApiImpl();
        getServer().getServicesManager().register(OutpostsApi.class, api, this, ServicePriority.Normal);
        core.services().register(OutpostsApi.class, api);
        core.modules().register(this, "MiraOutposts");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Faction capture objectives, persistent ownership and multiplier API ready");

        resolveBoostersApi();

        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("MiraOutposts v" + getPluginMeta().getVersion()
                + " enabled with " + outposts.size() + " outpost(s).");
    }

    @Override
    public void onDisable() {
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
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "list" -> {
                msg(sender, "&6Mira Outposts");
                if (outposts.isEmpty()) {
                    msg(sender, "&7No outposts configured.");
                    return true;
                }
                for (Outpost outpost : outposts.values()) {
                    Capture capture = captures.get(outpost.id());
                    String owner = outpost.ownerName() == null ? "Unclaimed" : outpost.ownerName();
                    String state = capture == null ? "" : " &8| &e" + capture.factionName()
                            + " " + capture.seconds() + "/" + outpost.captureSeconds() + "s";
                    msg(sender, "&e" + outpost.id() + " &8| &7owner &f" + owner
                            + " &8| &7" + outpost.channel() + " x" + format(outpost.multiplier()) + state);
                }
                return true;
            }
            case "info" -> {
                if (args.length < 2) {
                    msg(sender, "&eUsage: /outpost info <id>");
                    return true;
                }
                Outpost outpost = outposts.get(sanitizeId(args[1]));
                if (outpost == null) {
                    msg(sender, "&cOutpost not found.");
                    return true;
                }
                Capture capture = captures.get(outpost.id());
                msg(sender, "&6" + outpost.id() + " &7Owner: &f"
                        + (outpost.ownerName() == null ? "Unclaimed" : outpost.ownerName()));
                msg(sender, "&7World: &f" + outpost.world() + " &7Center: &f"
                        + format(outpost.x()) + ", " + format(outpost.y()) + ", " + format(outpost.z()));
                msg(sender, "&7Radius: &f" + outpost.radius()
                        + " &7Capture: &f" + outpost.captureSeconds() + "s"
                        + " &7Buff: &f" + outpost.channel() + " x" + format(outpost.multiplier()));
                if (capture != null) {
                    msg(sender, "&7Capturing: &f" + capture.factionName()
                            + " &7Progress: &f" + capture.seconds() + "/" + outpost.captureSeconds() + "s");
                }
                return true;
            }
            case "create" -> {
                if (!sender.hasPermission("miraoutposts.admin") || !(sender instanceof Player player)) {
                    msg(sender, "&cAn admin player is required.");
                    return true;
                }
                if (args.length < 6) {
                    msg(sender, "&eUsage: /outpost create <id> <radius> <captureSeconds> <channel> <multiplier>");
                    return true;
                }
                if (factions.isSafeZone(player.getLocation())) {
                    msg(sender, "&cOutposts cannot be created inside a SafeZone.");
                    return true;
                }

                String id = sanitizeId(args[1]);
                if (id.isBlank()) {
                    msg(sender, "&cOutpost ID is invalid.");
                    return true;
                }
                if (outposts.containsKey(id)) {
                    msg(sender, "&cAn outpost with that ID already exists.");
                    return true;
                }

                int radius;
                int seconds;
                double multiplier;
                try {
                    radius = Integer.parseInt(args[2]);
                    seconds = Integer.parseInt(args[3]);
                    multiplier = Double.parseDouble(args[5]);
                } catch (NumberFormatException exception) {
                    msg(sender, "&cInvalid radius, capture seconds or multiplier.");
                    return true;
                }

                int maxRadius = Math.max(3, getConfig().getInt("creation.max-radius", 128));
                int maxCaptureSeconds = Math.max(5, getConfig().getInt("creation.max-capture-seconds", 3600));
                double maxMultiplier = Math.max(1D, getConfig().getDouble("creation.max-multiplier", 100D));

                if (radius < 3 || radius > maxRadius) {
                    msg(sender, "&cRadius must be between 3 and " + maxRadius + ".");
                    return true;
                }
                if (seconds < 5 || seconds > maxCaptureSeconds) {
                    msg(sender, "&cCapture time must be between 5 and " + maxCaptureSeconds + " seconds.");
                    return true;
                }
                if (!Double.isFinite(multiplier) || multiplier < 1D || multiplier > maxMultiplier) {
                    msg(sender, "&cMultiplier must be finite and between 1 and " + maxMultiplier + ".");
                    return true;
                }

                String channel = sanitizeChannel(args[4]);
                if (channel.isBlank()) {
                    msg(sender, "&cMultiplier channel is invalid.");
                    return true;
                }

                Location location = player.getLocation();
                Outpost outpost = new Outpost(id, location.getWorld().getName(),
                        location.getX(), location.getY(), location.getZ(),
                        radius, seconds, channel, multiplier, null, null);
                outposts.put(id, outpost);
                save();

                core.audit().record("MiraOutposts", "OUTPOST_CREATED",
                        player.getUniqueId(), player.getName(), id, "Outpost created",
                        Map.of(
                                "world", outpost.world(),
                                "radius", Integer.toString(radius),
                                "captureSeconds", Integer.toString(seconds),
                                "channel", channel,
                                "multiplier", Double.toString(multiplier)
                        ));

                msg(sender, "&aCreated outpost &f" + id + "&a.");
                return true;
            }
            case "remove" -> {
                if (!sender.hasPermission("miraoutposts.admin") || args.length < 2) {
                    msg(sender, "&eUsage: /outpost remove <id>");
                    return true;
                }

                String id = sanitizeId(args[1]);
                Outpost removed = outposts.remove(id);
                captures.remove(id);
                if (removed == null) {
                    msg(sender, "&cOutpost not found.");
                    return true;
                }

                save();
                core.audit().record("MiraOutposts", "OUTPOST_REMOVED",
                        sender instanceof Player player ? player.getUniqueId() : null,
                        sender.getName(), id, "Outpost removed",
                        Map.of("previousOwner", removed.ownerId() == null ? "unclaimed" : removed.ownerId().toString()));

                msg(sender, "&aOutpost removed.");
                return true;
            }
            default -> {
                msg(sender, "&7/outpost <list|info <id>|create ...|remove <id>>");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("list", "info"));
            if (sender.hasPermission("miraoutposts.admin")) values.addAll(List.of("create", "remove"));
            return complete(args[0], values);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("remove"))) {
            return complete(args[1], outposts.keySet());
        }
        return List.of();
    }

    private void tick() {
        boolean resetEmpty = getConfig().getBoolean("capture.reset-when-empty", true);
        boolean resetContested = getConfig().getBoolean("capture.reset-when-contested", true);

        for (Outpost outpost : new ArrayList<>(outposts.values())) {
            World world = Bukkit.getWorld(outpost.world());
            if (world == null) continue;

            Location center = new Location(world, outpost.x(), outpost.y(), outpost.z());
            Map<UUID, String> factionsPresent = new LinkedHashMap<>();

            for (Player player : world.getPlayers()) {
                if (player.isDead() || player.getLocation().distanceSquared(center)
                        > (double) outpost.radius() * outpost.radius()) continue;

                Optional<UUID> factionId = factions.factionId(player.getUniqueId());
                if (factionId.isEmpty()) continue;

                String factionName = factions.factionName(player.getUniqueId())
                        .orElse(factionId.get().toString());
                factionsPresent.putIfAbsent(factionId.get(), factionName);
            }

            if (factionsPresent.isEmpty()) {
                if (resetEmpty) captures.remove(outpost.id());
                continue;
            }
            if (factionsPresent.size() > 1) {
                if (resetContested) captures.remove(outpost.id());
                continue;
            }

            UUID factionId = factionsPresent.keySet().iterator().next();
            String factionName = factionsPresent.get(factionId);

            if (factionId.equals(outpost.ownerId())) {
                captures.remove(outpost.id());
                continue;
            }

            Capture current = captures.get(outpost.id());
            if (current == null || !current.factionId().equals(factionId)) {
                current = new Capture(factionId, factionName, 0);
            }

            Capture progressed = new Capture(current.factionId(), current.factionName(), current.seconds() + 1);
            captures.put(outpost.id(), progressed);

            if (progressed.seconds() >= outpost.captureSeconds()) {
                completeCapture(outpost, progressed);
            }
        }
    }

    private void completeCapture(Outpost previous, Capture capture) {
        Outpost captured = new Outpost(previous.id(), previous.world(),
                previous.x(), previous.y(), previous.z(),
                previous.radius(), previous.captureSeconds(),
                previous.channel(), previous.multiplier(),
                capture.factionId(), capture.factionName());

        outposts.put(previous.id(), captured);
        captures.remove(previous.id());
        save();

        Bukkit.getPluginManager().callEvent(new OutpostCapturedEvent(
                view(captured), previous.ownerId(), previous.ownerName()));

        core.audit().record("MiraOutposts", "OUTPOST_CAPTURED",
                null, capture.factionName(), captured.id(), "Outpost captured",
                Map.of(
                        "factionId", capture.factionId().toString(),
                        "factionName", capture.factionName(),
                        "previousOwner", previous.ownerId() == null ? "unclaimed" : previous.ownerId().toString(),
                        "channel", captured.channel(),
                        "multiplier", Double.toString(captured.multiplier())
                ));

        broadcast("&6[Outpost] &f" + capture.factionName()
                + " &7captured &e" + captured.id()
                + " &7and now holds &f" + captured.channel()
                + " x" + format(captured.multiplier()) + "&7.");
    }

    private double factionMultiplier(UUID factionId, String channel) {
        if (factionId == null || channel == null || channel.isBlank()) return 1D;

        double result = 1D;
        double maximum = Math.max(1D, getConfig().getDouble("api.max-effective-multiplier", 1000D));
        for (Outpost outpost : outposts.values()) {
            if (!factionId.equals(outpost.ownerId()) || !outpost.channel().equalsIgnoreCase(channel)) continue;
            result = safeMultiply(result, outpost.multiplier(), maximum);
        }
        return result;
    }

    private double combinedPlayerMultiplier(UUID player, String channel) {
        double outpostMultiplier = factions.factionId(player)
                .map(factionId -> factionMultiplier(factionId, channel))
                .orElse(1D);

        if (!getConfig().getBoolean("boosters.combine-in-player-multiplier", true)) return outpostMultiplier;

        double booster = boosterMultiplier(channel, player);
        double maximum = Math.max(1D, getConfig().getDouble("api.max-effective-multiplier", 1000D));
        return safeMultiply(outpostMultiplier, booster, maximum);
    }

    private double boosterMultiplier(String channel, UUID player) {
        if (boostersApi == null && System.currentTimeMillis() - lastBoosterResolveAttempt > 30_000L) {
            resolveBoostersApi();
        }
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

            Object provider = registration.getProvider();
            Method multiplier = type.getMethod("multiplier", String.class, UUID.class);
            boostersApi = provider;
            boostersMultiplierMethod = multiplier;
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
    }

    public record OutpostView(String id, String world, double x, double y, double z,
                              int radius, int captureSeconds, String channel, double multiplier,
                              UUID ownerId, String ownerName) {
        public Location location() {
            World resolved = Bukkit.getWorld(world);
            return resolved == null ? null : new Location(resolved, x, y, z);
        }
    }

    public record CaptureView(String outpostId, UUID factionId, String factionName,
                              int seconds, int requiredSeconds) {
        public double progress() {
            return requiredSeconds <= 0 ? 0D : Math.min(1D, (double) seconds / requiredSeconds);
        }
    }

    private final class OutpostsApiImpl implements OutpostsApi {
        @Override
        public Collection<OutpostView> outposts() {
            return outposts.values().stream().map(MiraOutpostsPlugin::view).toList();
        }

        @Override
        public Optional<OutpostView> outpost(String id) {
            return Optional.ofNullable(outposts.get(sanitizeId(id))).map(MiraOutpostsPlugin::view);
        }

        @Override
        public Optional<CaptureView> capture(String id) {
            String normalized = sanitizeId(id);
            Outpost outpost = outposts.get(normalized);
            Capture capture = captures.get(normalized);
            if (outpost == null || capture == null) return Optional.empty();
            return Optional.of(new CaptureView(normalized, capture.factionId(), capture.factionName(),
                    capture.seconds(), outpost.captureSeconds()));
        }

        @Override
        public double multiplier(UUID factionId, String channel) {
            return factionMultiplier(factionId, channel);
        }

        @Override
        public double playerMultiplier(UUID player, String channel) {
            return combinedPlayerMultiplier(player, channel);
        }

        @Override
        public List<OutpostView> heldBy(UUID factionId) {
            return outposts.values().stream()
                    .filter(outpost -> factionId != null && factionId.equals(outpost.ownerId()))
                    .map(MiraOutpostsPlugin::view)
                    .toList();
        }
    }

    private void msg(CommandSender sender, String raw) {
        core.messages().send(sender, raw);
    }

    private void broadcast(String raw) {
        for (Player player : Bukkit.getOnlinePlayers()) core.messages().send(player, raw);
        core.messages().send(Bukkit.getConsoleSender(), raw);
    }

    private static OutpostView view(Outpost outpost) {
        return new OutpostView(outpost.id(), outpost.world(),
                outpost.x(), outpost.y(), outpost.z(),
                outpost.radius(), outpost.captureSeconds(),
                outpost.channel(), outpost.multiplier(),
                outpost.ownerId(), outpost.ownerName());
    }

    private static String sanitizeId(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_")
                .replaceAll("_+", "_").replaceAll("^_+|_+$", "");
    }

    private static String sanitizeChannel(String input) {
        return sanitizeId(input);
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted().toList();
    }

    private static double safeMultiply(double current, double adding, double maximum) {
        if (!Double.isFinite(current) || current <= 0D || !Double.isFinite(adding) || adding <= 0D) return 1D;
        double result = current * adding;
        if (!Double.isFinite(result)) return maximum;
        return Math.min(maximum, result);
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
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

                int radius = Math.max(3, root.getInt(base + "radius", 8));
                int captureSeconds = Math.max(5, root.getInt(base + "capture-seconds", 30));
                String channel = sanitizeChannel(root.getString(base + "channel", "shop_sell"));
                if (channel.isBlank()) channel = "shop_sell";

                double multiplier = root.getDouble(base + "multiplier", 1.1D);
                if (!Double.isFinite(multiplier) || multiplier < 1D) multiplier = 1D;

                String ownerText = root.getString(base + "owner-id");
                UUID ownerId = ownerText == null || ownerText.isBlank() ? null : UUID.fromString(ownerText);

                outposts.put(id, new Outpost(id, world,
                        root.getDouble(base + "x"),
                        root.getDouble(base + "y"),
                        root.getDouble(base + "z"),
                        radius, captureSeconds, channel, multiplier,
                        ownerId, root.getString(base + "owner-name")));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Outpost outpost : outposts.values()) {
            String base = "outposts." + outpost.id() + ".";
            yaml.set(base + "world", outpost.world());
            yaml.set(base + "x", outpost.x());
            yaml.set(base + "y", outpost.y());
            yaml.set(base + "z", outpost.z());
            yaml.set(base + "radius", outpost.radius());
            yaml.set(base + "capture-seconds", outpost.captureSeconds());
            yaml.set(base + "channel", outpost.channel());
            yaml.set(base + "multiplier", outpost.multiplier());
            yaml.set(base + "owner-id", outpost.ownerId() == null ? null : outpost.ownerId().toString());
            yaml.set(base + "owner-name", outpost.ownerName());
        }

        try {
            yaml.save(file);
        } catch (IOException exception) {
            getLogger().severe("Could not save outposts.yml: " + exception.getMessage());
        }
    }

    private record Outpost(String id, String world, double x, double y, double z,
                           int radius, int captureSeconds, String channel, double multiplier,
                           UUID ownerId, String ownerName) { }

    private record Capture(UUID factionId, String factionName, int seconds) { }
}
