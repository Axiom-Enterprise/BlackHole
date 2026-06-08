package org.bxteam.divinemc.config.command;

import com.mojang.brigadier.CommandDispatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import org.bxteam.divinemc.config.DivineConfig;

/**
 * {@code /axiomreload} — re-reads axiom.yml and re-applies the settings. Most settings are read once
 * at boot (they wire up thread pools, region backends and the chunk system); only runtime-safe ones
 * take effect on reload, so a restart is still needed for the rest.
 */
public final class AxiomReloadCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("axiomreload")
            .requires(listener -> listener.hasPermission(Permissions.COMMANDS_GAMEMASTER, "axiom.command.reload"))
            .executes(context -> execute(context.getSource())));
    }

    private static int execute(CommandSourceStack source) {
        DivineConfig.reload();
        source.sendSuccess(Component.text("axiom.yml reloaded.", NamedTextColor.GREEN), false);
        return 1;
    }
}
