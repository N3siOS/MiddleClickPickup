package dev.nesios.mdcpickup;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;
import dev.nesios.mdcpickup.config.PickConfig;
import dev.nesios.mdcpickup.interaction.MdcPickupPickBlockInteraction;
import dev.nesios.mdcpickup.system.PickInteractionSystem;

import javax.annotation.Nonnull;

/**
 * MdcPickup (mdcpickup) - enables pick-block in Adventure mode: middle-click
 * on a block selects the matching item from the player's inventory, like in
 * Creative.
 */
public final class MdcPickupPlugin extends JavaPlugin {

    private final Config<PickConfig> pickConfig;

    public MdcPickupPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        this.pickConfig = this.withConfig("mdc-pickup", PickConfig.CODEC);
    }

    @Override
    protected void setup() {
        this.pickConfig.save();

        // Asset packs are parsed during LoadAssetEvent, which is dispatched
        // after every plugin's setup(), so registering the custom interaction
        // type here guarantees the pack's child asset can resolve it.
        Interaction.CODEC.register(MdcPickupPickBlockInteraction.INTERACTION_TYPE, MdcPickupPickBlockInteraction.class, MdcPickupPickBlockInteraction.CODEC);

        this.getEntityStoreRegistry().registerSystem(new PickInteractionSystem(this.pickConfig));
        this.getLogger().atInfo().log("MdcPickup: server-side pick-block interaction enabled for Adventure mode.");
    }
}
