package org.bxteam.divinemc.diagnostics.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.bxteam.divinemc.diagnostics.ReportUploader;

/**
 * Delivers an upload result to a command source on the main thread, rendering
 * the temporary link as a clickable component (open URL).
 */
final class CommandResponder {
    private CommandResponder() {
    }

    static void send(CommandSourceStack source, ReportUploader.Result result) {
        final Component message;
        if (result.ok) {
            message = Component.text("Axiom diagnostics report: ", NamedTextColor.GREEN)
                .append(Component.text(result.link, NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(result.link)));
        } else {
            message = Component.text("Diagnostics upload failed: " + result.error, NamedTextColor.RED);
        }
        MinecraftServer.getServer().execute(() -> source.sendSuccess(message, false));
    }
}
