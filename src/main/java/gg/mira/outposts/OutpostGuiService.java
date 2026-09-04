package gg.mira.outposts;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class OutpostGuiService {
    private static final int[] LIST_SLOTS = {
            10,11,12,13,14,15,16,
            19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,
            37,38,39,40,41,42,43
    };

    private final MiraOutpostsPlugin plugin;
    private final FaweSelectionService fawe;
    private final Map<UUID, PendingInput> pending = new HashMap<>();
    private final Map<UUID, DeleteArm> deleteArms = new HashMap<>();

    public OutpostGuiService(MiraOutpostsPlugin plugin, FaweSelectionService fawe) {
        this.plugin = plugin;
        this.fawe = fawe;
    }

    public void openMain(Player player, int requestedPage) {
        List<MiraOutpostsPlugin.Outpost> all = plugin.outpostsInternal().stream()
                .sorted(Comparator.comparing(MiraOutpostsPlugin.Outpost::id))
                .toList();

        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) LIST_SLOTS.length));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        Inventory inventory = Bukkit.createInventory(new MainHolder(page), 54,
                Component.text("Mira Outposts - Page " + (page + 1) + "/" + pages));
        fill(inventory);

        inventory.setItem(4, item(Material.WOODEN_AXE, "Create From FAWE Selection",
                "Use //pos1 and //pos2 first.",
                "The X/Z rectangle becomes the capture zone.",
                "Click to enter the new outpost ID in chat."));

        int start = page * LIST_SLOTS.length;
        for (int i = 0; i < LIST_SLOTS.length && start + i < all.size(); i++) {
            MiraOutpostsPlugin.Outpost outpost = all.get(start + i);
            MiraOutpostsPlugin.Capture capture = plugin.captureInternal(outpost.id());
            List<String> lore = new ArrayList<>();
            lore.add("State: " + (outpost.running() ? "RUNNING" : "STOPPED"));
            lore.add("Owner: " + (outpost.ownerName() == null ? "Unclaimed" : outpost.ownerName()));
            lore.add("Region: " + outpost.width() + " x " + outpost.depth() + " (" + outpost.area() + " blocks)");
            lore.add("Channel: " + outpost.channel());
            lore.add("Multiplier: x" + MiraOutpostsPlugin.format(outpost.multiplier()));
            lore.add("Capture: " + outpost.captureSeconds() + "s");
            if (capture != null) lore.add("Capturing: " + capture.factionName() + " " + capture.seconds() + "/" + outpost.captureSeconds() + "s");
            lore.add(scheduleLine(outpost));
            lore.add("");
            lore.add("Click to edit.");
            inventory.setItem(LIST_SLOTS[i], item(outpost.running() ? Material.LIME_BANNER : Material.GRAY_BANNER,
                    outpost.id(), lore.toArray(String[]::new)));
        }

        if (page > 0) inventory.setItem(45, item(Material.ARROW, "Previous Page"));
        inventory.setItem(47, item(Material.LIME_DYE, "Start All",
                "Starts every currently stopped outpost.",
                "Each run begins unclaimed."));
        inventory.setItem(49, item(Material.BARRIER, "Close"));
        inventory.setItem(51, item(Material.RED_DYE, "Stop All",
                "Stops every currently running outpost.",
                "Ownership is remembered, but bonuses become inactive."));
        if (page + 1 < pages) inventory.setItem(53, item(Material.ARROW, "Next Page"));

        player.openInventory(inventory);
    }

    public void openEditor(Player player, String id) {
        MiraOutpostsPlugin.Outpost outpost = plugin.outpostInternal(id);
        if (outpost == null) {
            plugin.msg(player, "&cThat outpost no longer exists.");
            openMain(player, 0);
            return;
        }

        Inventory inventory = Bukkit.createInventory(new EditorHolder(outpost.id()), 54,
                Component.text("Edit Outpost - " + outpost.id()));
        fill(inventory);

        inventory.setItem(4, item(outpost.running() ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                outpost.id() + " - " + (outpost.running() ? "RUNNING" : "STOPPED"),
                "Owner: " + (outpost.ownerName() == null ? "Unclaimed" : outpost.ownerName()),
                "Current channel: " + outpost.channel() + " x" + MiraOutpostsPlugin.format(outpost.multiplier())));

        inventory.setItem(10, item(Material.WOODEN_AXE, "FAWE Region",
                "World: " + outpost.world(),
                "X: " + outpost.minX() + " -> " + outpost.maxX(),
                "Z: " + outpost.minZ() + " -> " + outpost.maxZ(),
                "Size: " + outpost.width() + " x " + outpost.depth(),
                "",
                "Click to replace this region",
                "with your current //pos1 //pos2 selection."));

        inventory.setItem(12, item(Material.CLOCK, "Capture Time",
                outpost.captureSeconds() + " seconds",
                "",
                "Click and type a new value in chat."));

        inventory.setItem(14, item(Material.HOPPER, "Channel",
                "Current: " + outpost.channel(),
                "",
                "Click to browse all built-in channel examples."));

        inventory.setItem(16, item(Material.GOLD_INGOT, "Multiplier",
                "Current: x" + MiraOutpostsPlugin.format(outpost.multiplier()),
                "",
                "Click and type a new multiplier in chat."));

        inventory.setItem(28, item(Material.LIME_DYE, "Start",
                outpost.running() ? "Already running." : "Start this outpost immediately.",
                "A new run begins unclaimed."));

        inventory.setItem(30, item(Material.RED_DYE, "Stop",
                outpost.running() ? "Stop this outpost immediately." : "Already stopped.",
                "While stopped, its multiplier is inactive."));

        inventory.setItem(32, item(Material.REPEATER, "Schedule",
                scheduleLine(outpost),
                "",
                "Click to choose how often it auto-starts."));

        inventory.setItem(34, item(Material.DAYLIGHT_DETECTOR, "Scheduled Run Length",
                "Current: " + formatDuration(outpost.scheduledRunSeconds()),
                "",
                "Only applies to automatic scheduled runs.",
                "Click and enter minutes in chat."));

        boolean armed = isDeleteArmed(player, outpost.id());
        inventory.setItem(40, item(Material.TNT, armed ? "CONFIRM DELETE" : "Delete Outpost",
                armed ? "Click again within 10 seconds to permanently delete." : "First click arms deletion.",
                "This does not alter the world selection itself."));

        inventory.setItem(49, item(Material.ARROW, "Back"));
        player.openInventory(inventory);
    }

    public void openChannels(Player player, String id) {
        MiraOutpostsPlugin.Outpost outpost = plugin.outpostInternal(id);
        if (outpost == null) return;

        Inventory inventory = Bukkit.createInventory(new ChannelHolder(id), 54,
                Component.text("Choose Channel - " + id));
        fill(inventory);

        int[] slots = {10,12,14,16,28};
        for (int i = 0; i < MiraOutpostsPlugin.CHANNELS.size(); i++) {
            MiraOutpostsPlugin.ChannelExample channel = MiraOutpostsPlugin.CHANNELS.get(i);
            inventory.setItem(slots[i], item(channel.icon(), channel.name(),
                    "ID: " + channel.id(),
                    channel.description(),
                    "",
                    outpost.channel().equalsIgnoreCase(channel.id()) ? "CURRENT CHANNEL" : "Click to select."));
        }

        inventory.setItem(32, item(Material.NAME_TAG, "Custom Channel",
                "For future/custom integrations.",
                "Click and type a channel ID in chat.",
                "A custom ID only has an effect if another plugin consumes it."));
        inventory.setItem(49, item(Material.ARROW, "Back"));
        player.openInventory(inventory);
    }

    public void openSchedule(Player player, String id) {
        MiraOutpostsPlugin.Outpost outpost = plugin.outpostInternal(id);
        if (outpost == null) return;

        Inventory inventory = Bukkit.createInventory(new ScheduleHolder(id), 54,
                Component.text("Schedule - " + id));
        fill(inventory);

        ScheduleOption[] options = {
                new ScheduleOption(0L, Material.BARRIER, "Disabled"),
                new ScheduleOption(1800L, Material.CLOCK, "Every 30 minutes"),
                new ScheduleOption(3600L, Material.CLOCK, "Every 1 hour"),
                new ScheduleOption(7200L, Material.CLOCK, "Every 2 hours"),
                new ScheduleOption(14400L, Material.CLOCK, "Every 4 hours"),
                new ScheduleOption(21600L, Material.CLOCK, "Every 6 hours"),
                new ScheduleOption(43200L, Material.CLOCK, "Every 12 hours"),
                new ScheduleOption(86400L, Material.CLOCK, "Every 24 hours")
        };
        int[] slots = {10,11,12,13,14,15,16,22};

        for (int i = 0; i < options.length; i++) {
            ScheduleOption option = options[i];
            boolean current = outpost.scheduleIntervalSeconds() == option.seconds();
            inventory.setItem(slots[i], item(option.icon(), option.name(),
                    current ? "CURRENT SCHEDULE" : "Click to select.",
                    option.seconds() == 0 ? "Manual Start/Stop only." :
                            "Automatic run length: " + formatDuration(outpost.scheduledRunSeconds())));
        }

        inventory.setItem(49, item(Material.ARROW, "Back"));
        player.openInventory(inventory);
    }

    void beginCreate(Player player) {
        FaweSelectionService.SelectionResult result = fawe.selection(player);
        if (!result.success()) {
            plugin.msg(player, "&c" + result.error());
            return;
        }
        FaweSelectionService.SelectionBounds b = result.bounds();
        player.closeInventory();
        pending.put(player.getUniqueId(), new PendingInput(InputType.CREATE_ID, null));
        plugin.msg(player, "&eType the new outpost ID in chat. &7Selection: &f"
                + b.world() + " " + b.width() + "x" + b.depth() + "&7. Type &fcancel &7to abort.");
    }

    void prompt(Player player, InputType type, String outpostId, String message) {
        player.closeInventory();
        pending.put(player.getUniqueId(), new PendingInput(type, outpostId));
        plugin.msg(player, message + " &7Type &fcancel &7to abort.");
    }

    MiraOutpostsPlugin plugin() { return plugin; }

    String outpostAt(int page, int rawSlot) {
        int index = -1;
        for (int i = 0; i < LIST_SLOTS.length; i++) {
            if (LIST_SLOTS[i] == rawSlot) { index = i; break; }
        }
        if (index < 0) return null;
        List<MiraOutpostsPlugin.Outpost> all = plugin.outpostsInternal().stream()
                .sorted(Comparator.comparing(MiraOutpostsPlugin.Outpost::id)).toList();
        int actual = page * LIST_SLOTS.length + index;
        return actual >= 0 && actual < all.size() ? all.get(actual).id() : null;
    }

    String channelAt(int rawSlot) {
        int[] slots = {10,12,14,16,28};
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == rawSlot && i < MiraOutpostsPlugin.CHANNELS.size()) {
                return MiraOutpostsPlugin.CHANNELS.get(i).id();
            }
        }
        return null;
    }

    Long scheduleAt(int rawSlot) {
        int[] slots = {10,11,12,13,14,15,16,22};
        long[] seconds = {0L,1800L,3600L,7200L,14400L,21600L,43200L,86400L};
        for (int i = 0; i < slots.length; i++) if (slots[i] == rawSlot) return seconds[i];
        return null;
    }

    PendingInput pending(UUID player) { return pending.get(player); }
    void clearPending(UUID player) { pending.remove(player); }

    void handleInput(Player player, String text, PendingInput input) {
        clearPending(player.getUniqueId());
        if (text.equalsIgnoreCase("cancel")) {
            plugin.msg(player, "&eInput cancelled.");
            if (input.outpostId() != null) openEditor(player, input.outpostId());
            else openMain(player, 0);
            return;
        }

        try {
            switch (input.type()) {
                case CREATE_ID -> {
                    if (plugin.createFromSelection(player, text)) {
                        plugin.msg(player, "&aOutpost created from your FAWE selection.");
                        openEditor(player, MiraOutpostsPlugin.sanitizeId(text));
                    } else openMain(player, 0);
                }
                case CAPTURE_SECONDS -> {
                    int seconds = Integer.parseInt(text);
                    if (seconds < 5 || seconds > plugin.maxCaptureSeconds()) {
                        plugin.msg(player, "&cCapture seconds must be between 5 and " + plugin.maxCaptureSeconds() + ".");
                    } else plugin.updateCaptureSeconds(input.outpostId(), seconds, player);
                    openEditor(player, input.outpostId());
                }
                case MULTIPLIER -> {
                    double multiplier = Double.parseDouble(text);
                    if (!Double.isFinite(multiplier) || multiplier < 1D || multiplier > plugin.maxMultiplier()) {
                        plugin.msg(player, "&cMultiplier must be between 1 and " + plugin.maxMultiplier() + ".");
                    } else plugin.updateMultiplier(input.outpostId(), multiplier, player);
                    openEditor(player, input.outpostId());
                }
                case RUN_MINUTES -> {
                    long minutes = Long.parseLong(text);
                    if (minutes < 1L || minutes > 10080L) {
                        plugin.msg(player, "&cRun length must be between 1 and 10080 minutes.");
                    } else {
                        MiraOutpostsPlugin.Outpost outpost = plugin.outpostInternal(input.outpostId());
                        if (outpost != null) plugin.setSchedule(input.outpostId(),
                                outpost.scheduleIntervalSeconds(), minutes * 60L, player);
                    }
                    openEditor(player, input.outpostId());
                }
                case CUSTOM_CHANNEL -> {
                    String channel = MiraOutpostsPlugin.sanitizeChannel(text);
                    if (channel.isBlank()) plugin.msg(player, "&cInvalid channel ID.");
                    else plugin.updateChannel(input.outpostId(), channel, player);
                    openEditor(player, input.outpostId());
                }
            }
        } catch (NumberFormatException exception) {
            plugin.msg(player, "&cThat value was not a valid number.");
            if (input.outpostId() != null) openEditor(player, input.outpostId());
            else openMain(player, 0);
        }
    }

    boolean armOrConfirmDelete(Player player, String id) {
        long now = System.currentTimeMillis();
        DeleteArm existing = deleteArms.get(player.getUniqueId());
        if (existing != null && existing.id().equals(id) && existing.expiresAt() >= now) {
            deleteArms.remove(player.getUniqueId());
            return true;
        }
        deleteArms.put(player.getUniqueId(), new DeleteArm(id, now + 10_000L));
        return false;
    }

    private boolean isDeleteArmed(Player player, String id) {
        DeleteArm arm = deleteArms.get(player.getUniqueId());
        return arm != null && arm.id().equals(id) && arm.expiresAt() >= System.currentTimeMillis();
    }

    private static String scheduleLine(MiraOutpostsPlugin.Outpost outpost) {
        if (outpost.scheduleIntervalSeconds() <= 0L) return "Schedule: Manual only";
        return "Schedule: every " + formatDuration(outpost.scheduleIntervalSeconds())
                + ", run " + formatDuration(outpost.scheduledRunSeconds());
    }

    static String formatDuration(long seconds) {
        if (seconds <= 0L) return "Off";
        if (seconds % 86400L == 0L) return (seconds / 86400L) + "d";
        if (seconds % 3600L == 0L) return (seconds / 3600L) + "h";
        if (seconds % 60L == 0L) return (seconds / 60L) + "m";
        return seconds + "s";
    }

    static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static void fill(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
    }

    enum InputType { CREATE_ID, CAPTURE_SECONDS, MULTIPLIER, RUN_MINUTES, CUSTOM_CHANNEL }
    record PendingInput(InputType type, String outpostId) { }
    record DeleteArm(String id, long expiresAt) { }
    record ScheduleOption(long seconds, Material icon, String name) { }

    public record MainHolder(int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
    public record EditorHolder(String id) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
    public record ChannelHolder(String id) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
    public record ScheduleHolder(String id) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
