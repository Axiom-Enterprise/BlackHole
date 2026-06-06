package org.bxteam.divinemc.diagnostics.command;

import com.mojang.brigadier.CommandDispatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.diagnostics.DiagnosticsSession;

/**
 * {@code /axiommetrics} — captures an instant combined metrics snapshot
 * (system, heap, GC, JIT, threads, TPS/MSPT) and returns a temporary viewer link.
 */
public final class AxiomMetricsCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("axiommetrics")
            .requires(listener -> listener.hasPermission(Permissions.COMMANDS_GAMEMASTER, "axiom.command.diagnostics"))
            .executes(context -> execute(context.getSource())));
    }

    private static int execute(CommandSourceStack source) {
        if (!DivineConfig.diagnosticsEnabled) {
            reply(source, "<red>Axiom diagnostics are disabled in the config.");
            return 0;
        }
        reply(source, "<gray>Capturing server metrics, uploading report...");
        DiagnosticsSession.metrics(result -> CommandResponder.send(source, result));
        return 1;
    }

    private static void reply(CommandSourceStack source, String mini) {
        final Component msg = MiniMessage.miniMessage().deserialize(mini);
        MinecraftServer.getServer().execute(() -> source.sendSuccess(msg, false));
    }
}
