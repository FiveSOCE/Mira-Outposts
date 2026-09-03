package gg.mira.outposts;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

public final class MiraOutpostsPlugin extends JavaPlugin {
    private static final String PREFIX = "&5&lMira &8>> &r";
    private final Map<String, Outpost> outposts = new LinkedHashMap<>();
    private final Map<String, Capture> captures = new HashMap<>();
    private File file;
    private OutpostsApi api;

    @Override public void onEnable() {
        file = new File(getDataFolder(), "outposts.yml"); load();
        api = new OutpostsApiImpl();
        getServer().getServicesManager().register(OutpostsApi.class, api, this, ServicePriority.Normal);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override public void onDisable() { save(); getServer().getServicesManager().unregisterAll(this); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            msg(sender, "&6Mira Outposts");
            for (Outpost o : outposts.values()) msg(sender, "&e" + o.id + " &7owner &f" + (o.ownerName == null ? "Unclaimed" : o.ownerName) + " &7buff &f" + o.channel + " x" + o.multiplier);
            return true;
        }
        if (args[0].equalsIgnoreCase("create")) {
            if (!sender.hasPermission("miraoutposts.admin") || !(sender instanceof Player player)) { msg(sender, "&cAdmin player required."); return true; }
            if (args.length < 6) { msg(sender, "&cUsage: /outpost create <id> <radius> <captureSeconds> <channel> <multiplier>"); return true; }
            try {
                String id = sanitize(args[1]); int radius = Math.max(3, Integer.parseInt(args[2])); int seconds = Math.max(5, Integer.parseInt(args[3])); double multiplier = Math.max(1D, Double.parseDouble(args[5]));
                Location l = player.getLocation(); Outpost o = new Outpost(id, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), radius, seconds, args[4].toLowerCase(Locale.ROOT), multiplier, null, null);
                outposts.put(id, o); save(); msg(sender, "&aCreated outpost " + id + ".");
            } catch (NumberFormatException ex) { msg(sender, "&cInvalid radius, capture seconds or multiplier."); }
            return true;
        }
        if (args[0].equalsIgnoreCase("remove")) {
            if (!sender.hasPermission("miraoutposts.admin") || args.length < 2) { msg(sender, "&cUsage: /outpost remove <id>"); return true; }
            Outpost removed = outposts.remove(sanitize(args[1])); captures.remove(sanitize(args[1])); if (removed == null) msg(sender, "&cOutpost not found."); else { save(); msg(sender, "&aOutpost removed."); } return true;
        }
        if (args[0].equalsIgnoreCase("info")) {
            if (args.length < 2) { msg(sender, "&cUsage: /outpost info <id>"); return true; }
            Outpost o = outposts.get(sanitize(args[1])); if (o == null) { msg(sender, "&cOutpost not found."); return true; }
            Capture c = captures.get(o.id);
            msg(sender, "&6" + o.id + " &7Owner: &f" + (o.ownerName == null ? "Unclaimed" : o.ownerName));
            msg(sender, "&7Radius: &f" + o.radius + " &7Capture: &f" + o.captureSeconds + "s &7Buff: &f" + o.channel + " x" + o.multiplier);
            if (c != null) msg(sender, "&7Capturing: &f" + c.factionName + " &7Progress: &f" + c.seconds + "/" + o.captureSeconds + "s");
            return true;
        }
        msg(sender, "&7/outpost list, info <id>, create ..., remove <id>"); return true;
    }

    private void tick() {
        Object factions = factionsApi();
        if (factions == null) return;
        for (Outpost o : new ArrayList<>(outposts.values())) {
            World world = Bukkit.getWorld(o.world); if (world == null) continue;
            Location center = new Location(world, o.x, o.y, o.z);
            Map<UUID, String> present = new HashMap<>();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(center) > (double)o.radius * o.radius) continue;
                FactionIdentity faction = factionOf(factions, p.getUniqueId()); if (faction != null) present.put(faction.id, faction.name);
            }
            if (present.size() != 1) { captures.remove(o.id); continue; }
            UUID factionId = present.keySet().iterator().next(); String factionName = present.get(factionId);
            if (factionId.equals(o.ownerId)) { captures.remove(o.id); continue; }
            Capture c = captures.get(o.id);
            if (c == null || !c.factionId.equals(factionId)) c = new Capture(factionId, factionName, 0);
            c = new Capture(c.factionId, c.factionName, c.seconds + 1); captures.put(o.id, c);
            if (c.seconds >= o.captureSeconds) {
                Outpost captured = new Outpost(o.id, o.world, o.x, o.y, o.z, o.radius, o.captureSeconds, o.channel, o.multiplier, factionId, factionName);
                outposts.put(o.id, captured); captures.remove(o.id); save();
                broadcast("&6[Outpost] &f" + factionName + " &7captured &e" + o.id + " &7and now holds &f" + o.channel + " x" + o.multiplier + "&7.");
            }
        }
    }

    private Object factionsApi() {
        try {
            Class<?> clazz = Class.forName("com.mira.factions.api.MiraFactionsApi");
            @SuppressWarnings({"rawtypes","unchecked"}) RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration((Class)clazz);
            return reg == null ? null : reg.getProvider();
        } catch (ClassNotFoundException ex) { return null; }
    }

    private FactionIdentity factionOf(Object api, UUID player) {
        try {
            Method idMethod = api.getClass().getMethod("factionId", UUID.class); Method nameMethod = api.getClass().getMethod("factionName", UUID.class);
            Optional<?> id = (Optional<?>)idMethod.invoke(api, player); Optional<?> name = (Optional<?>)nameMethod.invoke(api, player);
            if (id.isEmpty()) return null; return new FactionIdentity((UUID)id.get(), name.map(Object::toString).orElse(id.get().toString()));
        } catch (Exception ex) { return null; }
    }

    public interface OutpostsApi {
        Collection<OutpostView> outposts();
        Optional<OutpostView> outpost(String id);
        double multiplier(UUID factionId, String channel);
        List<OutpostView> heldBy(UUID factionId);
    }

    public record OutpostView(String id, String world, double x, double y, double z, int radius, String channel, double multiplier, UUID ownerId, String ownerName) {}

    private final class OutpostsApiImpl implements OutpostsApi {
        @Override public Collection<OutpostView> outposts() { return outposts.values().stream().map(MiraOutpostsPlugin::view).toList(); }
        @Override public Optional<OutpostView> outpost(String id) { return Optional.ofNullable(outposts.get(sanitize(id))).map(MiraOutpostsPlugin::view); }
        @Override public double multiplier(UUID factionId, String channel) {
            double result = 1D; for (Outpost o : outposts.values()) if (factionId != null && factionId.equals(o.ownerId) && o.channel.equalsIgnoreCase(channel)) result *= o.multiplier; return result;
        }
        @Override public List<OutpostView> heldBy(UUID factionId) { return outposts.values().stream().filter(o -> factionId != null && factionId.equals(o.ownerId)).map(MiraOutpostsPlugin::view).toList(); }
    }

    private void msg(CommandSender sender, String raw) { sender.sendMessage(ChatColor.translateAlternateColorCodes('&', PREFIX + raw)); }
    private void broadcast(String raw) { Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', PREFIX + raw)); }
    private static OutpostView view(Outpost o) { return new OutpostView(o.id,o.world,o.x,o.y,o.z,o.radius,o.channel,o.multiplier,o.ownerId,o.ownerName); }
    private static String sanitize(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"); }

    private void load() {
        getDataFolder().mkdirs(); YamlConfiguration y = YamlConfiguration.loadConfiguration(file); var root = y.getConfigurationSection("outposts"); if (root == null) return;
        for (String id : root.getKeys(false)) try { String b=id+"."; String ownerText=root.getString(b+"owner-id"); outposts.put(id,new Outpost(id,root.getString(b+"world"),root.getDouble(b+"x"),root.getDouble(b+"y"),root.getDouble(b+"z"),root.getInt(b+"radius",8),root.getInt(b+"capture-seconds",30),root.getString(b+"channel","shop_sell"),root.getDouble(b+"multiplier",1.1),ownerText==null?null:UUID.fromString(ownerText),root.getString(b+"owner-name"))); } catch(Exception ignored) {}
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration(); for (Outpost o:outposts.values()) { String b="outposts."+o.id+"."; y.set(b+"world",o.world);y.set(b+"x",o.x);y.set(b+"y",o.y);y.set(b+"z",o.z);y.set(b+"radius",o.radius);y.set(b+"capture-seconds",o.captureSeconds);y.set(b+"channel",o.channel);y.set(b+"multiplier",o.multiplier);y.set(b+"owner-id",o.ownerId==null?null:o.ownerId.toString());y.set(b+"owner-name",o.ownerName); }
        try { y.save(file); } catch(IOException ex) { getLogger().severe("Could not save outposts.yml: "+ex.getMessage()); }
    }

    private record Outpost(String id,String world,double x,double y,double z,int radius,int captureSeconds,String channel,double multiplier,UUID ownerId,String ownerName) {}
    private record Capture(UUID factionId,String factionName,int seconds) {}
    private record FactionIdentity(UUID id,String name) {}
}
