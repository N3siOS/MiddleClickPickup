package dev.nesios.mdcpickup.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Configuration for the pick-block mapping.
 *
 * <p>The item search and slot movement are performed server-side by the
 * {@code MdcPickup_PickBlock} interaction: hotbar match activates its slot,
 * otherwise one item is moved from storage or backpack into an empty or the
 * active hotbar slot.</p>
 */
public final class PickConfig {

    public static final BuilderCodec<PickConfig> CODEC = BuilderCodec.builder(PickConfig.class, PickConfig::new)
            .append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (config, value, extraInfo) -> config.enabled = value,
                    (config, extraInfo) -> config.enabled
            )
            .add()
            .append(
                    new KeyedCodec<>("SkipCreative", Codec.BOOLEAN),
                    (config, value, extraInfo) -> config.skipCreative = value,
                    (config, extraInfo) -> config.skipCreative
            )
            .add()
            .build();

    private boolean enabled = true;
    private boolean skipCreative = true;

    private PickConfig() {
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public boolean isSkipCreative() {
        return this.skipCreative;
    }
}
