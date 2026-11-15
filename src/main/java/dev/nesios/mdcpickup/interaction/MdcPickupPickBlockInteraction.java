package dev.nesios.mdcpickup.interaction;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Server-side implementation of the game's pick-block behaviour for
 * Adventure mode.
 *
 * <p>The vanilla {@code PickBlock} interaction is a client-side TODO in the
 * shared server sources: its {@code interactWithBlock} is empty and the
 * client never performs the pick outside Creative mode. This interaction
 * completes that behaviour on the server, following the patterns of the
 * official inventory interactions ({@code AddItemInteraction},
 * {@code ModifyInventoryInteraction}, {@code InventoryUtils}):</p>
 *
 * <ul>
 *   <li>If the target block's item is already in the hotbar, the matching
 *       slot becomes the active slot.</li>
 *   <li>Otherwise one item is moved from storage (then backpack) into an
 *       empty hotbar slot, or into the active slot when the hotbar is full
 *       (the displaced stack is put back into storage and drops only if the
 *       whole inventory is full).</li>
 *   <li>If the player owns the item nowhere, nothing happens.</li>
 * </ul>
 *
 * <p>The class extends {@link SimpleBlockInteraction} like every official
 * block interaction, so the client receives it as the generic
 * {@code SimpleBlockInteraction} protocol type and can run the chain without
 * knowing this server class. All mutations go through the regular ECS
 * inventory components, so the native client sync (inventory updates,
 * equipment network, active slot) applies unchanged.</p>
 */
public final class MdcPickupPickBlockInteraction extends SimpleBlockInteraction {

    /**
     * The type id this interaction is registered under in
     * {@code Interaction.CODEC}. Child interaction assets reference it via
     * {@code {"Type": "MdcPickup_PickBlock"}}.
     */
    public static final String INTERACTION_TYPE = "MdcPickup_PickBlock";

    /**
     * The codec used to parse {@link MdcPickupPickBlockInteraction} instances.
     */
    @Nonnull
    public static final BuilderCodec<MdcPickupPickBlockInteraction> CODEC =
        BuilderCodec.builder(MdcPickupPickBlockInteraction.class, MdcPickupPickBlockInteraction::new, SimpleBlockInteraction.CODEC)
            .documentation("Performs a server-side 'block pick': selects the target block's item in the hotbar if owned there, otherwise moves one from storage or backpack into an empty or the active hotbar slot.")
            .build();

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Override
    protected void interactWithBlock(@Nonnull final World world,
                                     @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull final InteractionType type,
                                     @Nonnull final InteractionContext context,
                                     @Nullable final ItemStack itemInHand,
                                     @Nonnull final Vector3i targetBlock,
                                     @Nonnull final CooldownHandler cooldownHandler) {
        final var ref = resolveActingEntity(context);
        if (ref == null) return;

        final var blockType = getTargetBlockType(world, targetBlock);
        if (blockType == null) return;

        final var item = blockType.getItem();
        if (item == null) return;
        final var itemId = item.getId();

        final var hotbarComponent = commandBuffer.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComponent == null) return;
        final var hotbar = hotbarComponent.getInventory();

        // Phase 1: the item is already in the hotbar -> activate its slot.
        for (short slot = 0; slot < hotbar.getCapacity(); slot++) {
            final var stack = hotbar.getItemStack(slot);
            if (!ItemStack.isEmpty(stack) && itemId.equals(stack.getItemId())) {
                hotbarComponent.setActiveSlot((byte) slot, ref, commandBuffer);
                syncActiveSlotToClient(commandBuffer, ref, hotbarComponent);
                LOGGER.at(Level.FINE).log("Pick block: activated hotbar slot %d for '%s'", slot, itemId);
                return;
            }
        }

        // Phase 2: move one item from storage, then backpack, into the hotbar.
        if (pickFromContainer(commandBuffer, ref, commandBuffer.getComponent(ref, InventoryComponent.Storage.getComponentType()), hotbarComponent, itemId)) {
            return;
        }
        pickFromContainer(commandBuffer, ref, commandBuffer.getComponent(ref, InventoryComponent.Backpack.getComponentType()), hotbarComponent, itemId);
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull final InteractionType type,
                                             @Nonnull final InteractionContext context,
                                             @Nullable final ItemStack itemInHand,
                                             @Nonnull final World world,
                                             @Nonnull final Vector3i targetBlock) {
        // No client-side simulation needed; the server performs the pick.
    }

    /**
     * Returns the acting entity of the chain: the owning entity (the player)
     * when valid, otherwise the entity that started the chain.
     */
    @Nullable
    private static Ref<EntityStore> resolveActingEntity(@Nonnull final InteractionContext context) {
        final var ownerRef = context.getOwningEntity();
        if (ownerRef != null && ownerRef.isValid()) return ownerRef;
        final var ref = context.getEntity();
        return ref != null && ref.isValid() ? ref : null;
    }

    /**
     * Reads the block type at the target position, mirroring the official
     * {@code BreakBlockInteraction}.
     */
    @Nullable
    private static BlockType getTargetBlockType(@Nonnull final World world, @Nonnull final Vector3i targetBlock) {
        final var chunkStore = world.getChunkStore();
        final var sectionReference = chunkStore.getChunkSectionReferenceAtBlock(targetBlock.x, targetBlock.y, targetBlock.z);
        if (sectionReference == null || !sectionReference.isValid()) return null;

        final var blockSection = chunkStore.getStore().getComponent(sectionReference, BlockSection.getComponentType());
        if (blockSection == null) return null;

        return BlockType.getAssetMap().getAsset(blockSection.get(targetBlock.x, targetBlock.y, targetBlock.z));
    }

    /**
     * Moves one item with the given id from the source container into the
     * hotbar: into the first empty hotbar slot, or into the active slot when
     * the hotbar is full.
     *
     * @return {@code true} when an item was picked.
     */
    private static boolean pickFromContainer(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                             @Nonnull final Ref<EntityStore> ref,
                                             @Nullable final InventoryComponent sourceComponent,
                                             @Nonnull final InventoryComponent.Hotbar hotbarComponent,
                                             @Nonnull final String itemId) {
        if (sourceComponent == null) return false;
        final var source = sourceComponent.getInventory();
        final var hotbar = hotbarComponent.getInventory();

        for (short slot = 0; slot < source.getCapacity(); slot++) {
            final var stack = source.getItemStack(slot);
            if (ItemStack.isEmpty(stack) || !itemId.equals(stack.getItemId())) continue;

            return pickFromSlot(commandBuffer, ref, source, slot, stack, hotbarComponent, hotbar);
        }
        return false;
    }

    /**
     * Moves one item from the given source slot into the hotbar.
     */
    private static boolean pickFromSlot(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull final Ref<EntityStore> ref,
                                        @Nonnull final ItemContainer source,
                                        final short sourceSlot,
                                        @Nonnull final ItemStack sourceStack,
                                        @Nonnull final InventoryComponent.Hotbar hotbarComponent,
                                        @Nonnull final ItemContainer hotbar) {
        final var toMove = sourceStack.withQuantity(1);

        // Preferred target: an empty hotbar slot.
        for (short slot = 0; slot < hotbar.getCapacity(); slot++) {
            if (!ItemStack.isEmpty(hotbar.getItemStack(slot))) continue;

            final var removeTransaction = source.removeItemStackFromSlot(sourceSlot, sourceStack, 1);
            if (!removeTransaction.succeeded()) return false;

            final var addTransaction = hotbar.addItemStackToSlot(slot, toMove);
            if (!addTransaction.succeeded()) {
                // Rollback: put the item back where it came from.
                SimpleItemContainer.addOrDropItemStack(commandBuffer, ref, source, sourceSlot, toMove);
                return false;
            }

            hotbarComponent.setActiveSlot((byte) slot, ref, commandBuffer);
            syncActiveSlotToClient(commandBuffer, ref, hotbarComponent);
            LOGGER.at(Level.FINE).log("Pick block: moved 1x '%s' into hotbar slot %d", toMove.getItemId(), slot);
            return true;
        }

        // Fallback: the hotbar is full -> swap with the active slot.
        final byte activeSlot = hotbarComponent.getActiveSlot();
        if (activeSlot == InventoryComponent.INACTIVE_SLOT_INDEX) return false;

        final var activeStack = hotbar.getItemStack(activeSlot);
        if (ItemStack.isEmpty(activeStack)) return false;

        final var removeTransaction = source.removeItemStackFromSlot(sourceSlot, sourceStack, 1);
        if (!removeTransaction.succeeded()) return false;

        final var replaceTransaction = hotbar.replaceItemStackInSlot(activeSlot, activeStack, toMove);
        if (!replaceTransaction.succeeded()) {
            SimpleItemContainer.addOrDropItemStack(commandBuffer, ref, source, sourceSlot, toMove);
            return false;
        }

        // Put the displaced stack back into hotbar/storage/backpack; it drops
        // only when the whole inventory is full (native addOrDrop semantics).
        final var combined = InventoryComponent.getCombined(commandBuffer, ref, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
        SimpleItemContainer.addOrDropItemStack(commandBuffer, ref, combined, activeStack);
        // The active slot itself did not change, but its content did: re-assert
        // it so the client re-reads the slot and refreshes the held item.
        syncActiveSlotToClient(commandBuffer, ref, hotbarComponent);
        LOGGER.at(Level.FINE).log("Pick block: swapped 1x '%s' into active hotbar slot %d", toMove.getItemId(), activeSlot);
        return true;
    }

    /**
     * Notifies the client that the hotbar's active slot changed on the server.
     *
     * <p>The client is authoritative for its own hotbar selector: without
     * this packet (the same one {@code InteractionManager} sends to correct
     * desynced players) the client keeps highlighting its previous slot until
     * its next own input triggers a resync.</p>
     */
    private static void syncActiveSlotToClient(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                               @Nonnull final Ref<EntityStore> ref,
                                               @Nonnull final InventoryComponent.Hotbar hotbarComponent) {
        final var playerRefComponent = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRefComponent == null) return;

        playerRefComponent.getPacketHandler().writeNoCache(
            new SetActiveSlot(InventoryComponent.HOTBAR_SECTION_ID, hotbarComponent.getActiveSlot()));
    }
}
