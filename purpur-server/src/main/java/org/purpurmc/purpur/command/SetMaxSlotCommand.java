package org.purpurmc.purpur.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.permissions.Permissions;
import org.purpurmc.purpur.PurpurConfig;

public class SetMaxSlotCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("setmaxslot")
                .requires(listener -> listener.hasPermission(Permissions.COMMANDS_ADMIN, "bukkit.command.setmaxslot"))
                .executes(context -> get(context.getSource()))
                .then(Commands.argument("max", IntegerArgumentType.integer(1))
                        .executes(context -> set(context.getSource(), IntegerArgumentType.getInteger(context, "max")))
                )
        );
    }

    private static int get(CommandSourceStack sender) {
        int max = sender.getServer().getPlayerList().getMaxPlayers();
        sender.sendSuccess(MiniMessage.miniMessage().deserialize(PurpurConfig.maxPlayersCommandGet,
                Placeholder.unparsed("max", Integer.toString(max))), false);
        return max;
    }

    private static int set(CommandSourceStack sender, int max) {
        MinecraftServer server = sender.getServer();
        if (server instanceof DedicatedServer dedicated) {
            dedicated.setMaxPlayers(max);
        }
        sender.sendSuccess(MiniMessage.miniMessage().deserialize(PurpurConfig.maxPlayersCommandSet,
                Placeholder.unparsed("max", Integer.toString(max))), false);
        return max;
    }
}
