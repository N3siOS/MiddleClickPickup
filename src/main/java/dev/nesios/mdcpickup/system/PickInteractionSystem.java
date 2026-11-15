package dev.nesios.mdcpickup.system;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;
import dev.nesios.mdcpickup.config.PickConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Makes the game's pick-block interaction available to Adventure players.
 *
 * <p>In vanilla Hytale the middle mouse button only runs the {@code Pick}
 * interaction when the player's {@link Interactions} component maps that
 * interaction type to a root interaction. Creative mode installs such a
 * mapping through its game mode asset; Adventure does not. This system adds
 * the missing mapping so the native Pick chain starts for Adventure
 * players.</p>
 *
 * <p>The chain itself ends in the mod's server-side
 * {@code MdcPickup_PickBlock} interaction, which performs the pick (see
 * {@link dev.nesios.mdcpickup.interaction.MdcPickupPickBlockInteraction}).</p>
 */
public final class PickInteractionSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Root interaction added by this mod. It must match the asset file in
     * {@code Server/Item/RootInteractions/Nesios_MdcPickup_Pick.json}.
     */
    public static final String PICK_ROOT_INTERACTION_ID = "Nesios_MdcPickup_Pick";

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(Player.getComponentType());

    @Nonnull
    private final Config<PickConfig> config;

    public PickInteractionSystem(@Nonnull Config<PickConfig> config) {
        this.config = config;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // The system may add a component to the player archetype. Keeping it
        // sequential avoids surprising interactions with parallel command
        // buffers while costing nothing for the handful of players online.
        return false;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PickConfig pickConfig = this.config.get();

        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (player == null || player.getGameMode() == null) {
            return;
        }

        Interactions interactions = archetypeChunk.getComponent(index, Interactions.getComponentType());

        if (pickConfig == null || !pickConfig.isEnabled()) {
            // Remove only our own mapping so a runtime config change takes
            // effect without disturbing mappings owned by other mods.
            if (interactions != null
                    && PICK_ROOT_INTERACTION_ID.equals(interactions.getInteractionId(InteractionType.Pick))) {
                interactions.removeInteractionId(InteractionType.Pick);
            }
            return;
        }

        if (player.getGameMode() == GameMode.Creative) {
            // Creative already has its own pick-block mapping. If the player
            // entered Creative while our mapping was installed, remove only
            // our mapping so the vanilla game mode mapping (if any) wins.
            if (pickConfig.isSkipCreative()
                    && interactions != null
                    && PICK_ROOT_INTERACTION_ID.equals(interactions.getInteractionId(InteractionType.Pick))) {
                interactions.removeInteractionId(InteractionType.Pick);
            }
            return;
        }

        if (interactions == null) {
            Interactions newInteractions = new Interactions();
            newInteractions.setInteractionId(InteractionType.Pick, PICK_ROOT_INTERACTION_ID);
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            commandBuffer.addComponent(ref, Interactions.getComponentType(), newInteractions);
            LOGGER.at(Level.INFO).log("Installed Pick mapping (new component) for a player in mode %s", player.getGameMode());
            return;
        }

        String existing = interactions.getInteractionId(InteractionType.Pick);
        // Leave mappings owned by other mods or custom game modes alone, but
        // replace bindings that no longer resolve (e.g. left over from an
        // older version of this mod or from a removed asset pack): the server
        // silently cancels every Pick chain whose root cannot be resolved.
        if (existing == null || !isPickRootAvailable(existing)) {
            if (existing != null) {
                LOGGER.at(Level.INFO).log("Replacing stale Pick mapping '%s' with '%s'", existing, PICK_ROOT_INTERACTION_ID);
            }
            interactions.setInteractionId(InteractionType.Pick, PICK_ROOT_INTERACTION_ID);
        }
    }

    private static boolean isPickRootAvailable(@Nullable String rootId) {
        return PICK_ROOT_INTERACTION_ID.equals(rootId)
                || RootInteraction.getAssetMap().getAsset(rootId) != null;
    }
}
