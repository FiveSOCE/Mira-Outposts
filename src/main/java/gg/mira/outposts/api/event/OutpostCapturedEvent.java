package gg.mira.outposts.api.event;

import gg.mira.outposts.MiraOutpostsPlugin.OutpostView;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class OutpostCapturedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final OutpostView outpost;
    private final UUID previousOwnerId;
    private final String previousOwnerName;

    public OutpostCapturedEvent(OutpostView outpost, UUID previousOwnerId, String previousOwnerName) {
        this.outpost = outpost;
        this.previousOwnerId = previousOwnerId;
        this.previousOwnerName = previousOwnerName;
    }

    public OutpostView outpost() { return outpost; }
    public UUID previousOwnerId() { return previousOwnerId; }
    public String previousOwnerName() { return previousOwnerName; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
