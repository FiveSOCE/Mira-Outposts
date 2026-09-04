package gg.mira.outposts;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class OutpostGuiListener implements Listener {
    private final OutpostGuiService gui;

    public OutpostGuiListener(OutpostGuiService gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Object holder = event.getView().getTopInventory().getHolder();

        if (!(holder instanceof OutpostGuiService.MainHolder)
                && !(holder instanceof OutpostGuiService.EditorHolder)
                && !(holder instanceof OutpostGuiService.ChannelHolder)
                && !(holder instanceof OutpostGuiService.ScheduleHolder)) {
            return;
        }

        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) return;

        if (holder instanceof OutpostGuiService.MainHolder main) {
            handleMain(player, main, raw);
        } else if (holder instanceof OutpostGuiService.EditorHolder editor) {
            handleEditor(player, editor, raw);
        } else if (holder instanceof OutpostGuiService.ChannelHolder channel) {
            handleChannel(player, channel, raw);
        } else if (holder instanceof OutpostGuiService.ScheduleHolder schedule) {
            handleSchedule(player, schedule, raw);
        }
    }

    private void handleMain(Player player, OutpostGuiService.MainHolder holder, int raw) {
        if (raw == 4) {
            if (!player.hasPermission("miraoutposts.admin")) {
                gui.plugin().msg(player, "&cYou do not have permission to create outposts.");
                return;
            }
            gui.beginCreate(player);
            return;
        }

        if (raw == 45) {
            gui.openMain(player, holder.page() - 1);
            return;
        }
        if (raw == 49) {
            player.closeInventory();
            return;
        }
        if (raw == 53) {
            gui.openMain(player, holder.page() + 1);
            return;
        }

        if (raw == 47 || raw == 51) {
            if (!player.hasPermission("miraoutposts.admin")) {
                gui.plugin().msg(player, "&cYou do not have permission.");
                return;
            }
            boolean start = raw == 47;
            int changed = 0;
            for (MiraOutpostsPlugin.Outpost outpost : gui.plugin().outpostsInternal()) {
                boolean success = start
                        ? gui.plugin().startOutpost(outpost.id(), false, player)
                        : gui.plugin().stopOutpost(outpost.id(), player, false);
                if (success) changed++;
            }
            gui.plugin().msg(player, (start ? "&aStarted &f" : "&eStopped &f") + changed + " &7outpost(s).");
            gui.openMain(player, holder.page());
            return;
        }

        String id = gui.outpostAt(holder.page(), raw);
        if (id != null) gui.openEditor(player, id);
    }

    private void handleEditor(Player player, OutpostGuiService.EditorHolder holder, int raw) {
        String id = holder.id();
        if (gui.plugin().outpostInternal(id) == null) {
            gui.openMain(player, 0);
            return;
        }

        if (!player.hasPermission("miraoutposts.admin")) {
            gui.plugin().msg(player, "&cYou do not have permission to edit outposts.");
            return;
        }

        switch (raw) {
            case 10 -> {
                if (gui.plugin().updateRegionFromFawe(id, player)) {
                    gui.plugin().msg(player, "&aOutpost region updated from your FAWE selection.");
                }
                gui.openEditor(player, id);
            }
            case 12 -> gui.prompt(player, OutpostGuiService.InputType.CAPTURE_SECONDS, id,
                    "&eType the new capture time in seconds.");
            case 14 -> gui.openChannels(player, id);
            case 16 -> gui.prompt(player, OutpostGuiService.InputType.MULTIPLIER, id,
                    "&eType the new reward multiplier.");
            case 28 -> {
                if (gui.plugin().startOutpost(id, false, player)) gui.plugin().msg(player, "&aOutpost started.");
                else gui.plugin().msg(player, "&eThat outpost is already running.");
                gui.openEditor(player, id);
            }
            case 30 -> {
                if (gui.plugin().stopOutpost(id, player, false)) gui.plugin().msg(player, "&eOutpost stopped.");
                else gui.plugin().msg(player, "&7That outpost is already stopped.");
                gui.openEditor(player, id);
            }
            case 32 -> gui.openSchedule(player, id);
            case 34 -> gui.prompt(player, OutpostGuiService.InputType.RUN_MINUTES, id,
                    "&eType the scheduled run length in minutes.");
            case 40 -> {
                if (gui.armOrConfirmDelete(player, id)) {
                    gui.plugin().removeOutpost(id, player);
                    gui.plugin().msg(player, "&cOutpost deleted.");
                    gui.openMain(player, 0);
                } else {
                    gui.plugin().msg(player, "&eDeletion armed. Click Delete again within 10 seconds to confirm.");
                    gui.openEditor(player, id);
                }
            }
            case 49 -> gui.openMain(player, 0);
            default -> { }
        }
    }

    private void handleChannel(Player player, OutpostGuiService.ChannelHolder holder, int raw) {
        if (raw == 49) {
            gui.openEditor(player, holder.id());
            return;
        }
        if (raw == 32) {
            gui.prompt(player, OutpostGuiService.InputType.CUSTOM_CHANNEL, holder.id(),
                    "&eType the custom channel ID.");
            return;
        }

        String channel = gui.channelAt(raw);
        if (channel != null) {
            gui.plugin().updateChannel(holder.id(), channel, player);
            gui.plugin().msg(player, "&aChannel set to &f" + channel + "&a.");
            gui.openEditor(player, holder.id());
        }
    }

    private void handleSchedule(Player player, OutpostGuiService.ScheduleHolder holder, int raw) {
        if (raw == 49) {
            gui.openEditor(player, holder.id());
            return;
        }

        Long seconds = gui.scheduleAt(raw);
        if (seconds == null) return;

        MiraOutpostsPlugin.Outpost outpost = gui.plugin().outpostInternal(holder.id());
        if (outpost == null) return;

        gui.plugin().setSchedule(holder.id(), seconds, outpost.scheduledRunSeconds(), player);
        gui.plugin().msg(player, seconds == 0L
                ? "&eAutomatic schedule disabled."
                : "&aSchedule set to every &f" + OutpostGuiService.formatDuration(seconds) + "&a.");
        gui.openEditor(player, holder.id());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof OutpostGuiService.MainHolder
                || holder instanceof OutpostGuiService.EditorHolder
                || holder instanceof OutpostGuiService.ChannelHolder
                || holder instanceof OutpostGuiService.ScheduleHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        OutpostGuiService.PendingInput input = gui.pending(event.getPlayer().getUniqueId());
        if (input == null) return;

        event.setCancelled(true);
        String message = event.getMessage();
        Bukkit.getScheduler().runTask(gui.plugin(), () ->
                gui.handleInput(event.getPlayer(), message, input));
    }
}
