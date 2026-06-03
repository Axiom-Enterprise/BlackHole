package org.bxteam.divinemc.config;

import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.ConfigurationSection;

import java.io.IOException;

@SuppressWarnings("unused")
public class DivineWorldConfig {
    private final String legacyWorldName;
    private final String worldName;
    private final World.Environment environment;

    public DivineWorldConfig(String legacyWorldName, World.Environment environment, Key worldKey) throws IOException {
        this.legacyWorldName = legacyWorldName;
        this.worldName = worldKey.asString();
        this.environment = environment;
        init();
    }

    public void init() throws IOException {
        if (DivineConfig.CONFIG_VERSION < 8) {
            ConfigurationSection section = DivineConfig.config.getConfigurationSection("world-settings." + this.legacyWorldName);
            if (section != null) {
                DivineConfig.config.set("world-settings." + this.legacyWorldName, null);
                DivineConfig.config.set("world-settings." + this.worldName, section);
                Bukkit.getLogger().info("NOTE: Migrated DivineMC world config %s -> %s".formatted(this.legacyWorldName, this.worldName));
            }
        }

        DivineConfig.readConfig(DivineWorldConfig.class, this);
    }

    public @Nullable String getString(String path, String def, String... comments) {
        DivineConfig.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) DivineConfig.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return DivineConfig.config.getString("world-settings." + this.worldName + "." + path, DivineConfig.config.getString("world-settings.default." + path));
    }

    public boolean getBoolean(String path, boolean def, String... comments) {
        DivineConfig.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) DivineConfig.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return DivineConfig.config.getBoolean("world-settings." + this.worldName + "." + path, DivineConfig.config.getBoolean("world-settings.default." + path));
    }

    public double getDouble(String path, double def, String... comments) {
        DivineConfig.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) DivineConfig.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return DivineConfig.config.getDouble("world-settings." + this.worldName + "." + path, DivineConfig.config.getDouble("world-settings.default." + path));
    }

    public int getInt(String path, int def, String... comments) {
        DivineConfig.config.addDefault("world-settings.default." + path, def);
        if (comments.length > 0) DivineConfig.config.setComment("world-settings.default." + path, String.join("\n", comments));
        return DivineConfig.config.getInt("world-settings." + this.worldName + "." + path, DivineConfig.config.getInt("world-settings.default." + path));
    }
}
