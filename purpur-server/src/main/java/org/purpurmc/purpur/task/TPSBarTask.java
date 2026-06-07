package org.purpurmc.purpur.task;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.purpurmc.purpur.PurpurConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;

public class TPSBarTask extends BossBarTask {
    private static TPSBarTask instance;
    private double tps = 20.0D;
    private double tps5m = 20.0D;
    private double tps15m = 20.0D;
    private double mspt = 0.0D;
    private double cpu = 0.0D;
    private int players = 0;
    private int chunks = 0;
    private int entities = 0;
    private int tick = 0;

    private final com.sun.management.OperatingSystemMXBean osBean = resolveOsBean();

    private static com.sun.management.OperatingSystemMXBean resolveOsBean() {
        try {
            return (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Throwable t) {
            return null;
        }
    }

    public static TPSBarTask instance() {
        if (instance == null) {
            instance = new TPSBarTask();
        }
        return instance;
    }

    @Override
    BossBar createBossBar() {
        return BossBar.bossBar(Component.text(""), 0.0F, instance().getBossBarColor(), PurpurConfig.commandTPSBarProgressOverlay);
    }

    @Override
    void updateBossBar(BossBar bossbar, Player player) {
        bossbar.progress(getBossBarProgress());
        bossbar.color(getBossBarColor());
        bossbar.name(MiniMessage.miniMessage().deserialize(PurpurConfig.commandTPSBarTitle,
                Placeholder.component("tps", getTPSColor()),
                Placeholder.component("tps5m", getTPSColor(tps5m)),
                Placeholder.component("tps15m", getTPSColor(tps15m)),
                Placeholder.component("mspt", getMSPTColor()),
                Placeholder.component("ping", getPingColor(player.getPing())),
                Placeholder.unparsed("cpu", String.format("%.1f", cpu)),
                Placeholder.unparsed("players", Integer.toString(players)),
                Placeholder.unparsed("chunks", Integer.toString(chunks)),
                Placeholder.unparsed("entities", Integer.toString(entities))
        ));
    }

    @Override
    public void run() {
        if (++tick < PurpurConfig.commandTPSBarTickInterval) {
            return;
        }
        tick = 0;

        double[] tpsArr = Bukkit.getTPS();
        this.tps = Math.max(Math.min(tpsArr[0], 20.0D), 0.0D);
        this.tps5m = Math.max(Math.min(tpsArr.length > 1 ? tpsArr[1] : tpsArr[0], 20.0D), 0.0D);
        this.tps15m = Math.max(Math.min(tpsArr.length > 2 ? tpsArr[2] : tpsArr[0], 20.0D), 0.0D);
        this.mspt = Bukkit.getAverageTickTime();
        this.cpu = osBean != null ? Math.max(osBean.getProcessCpuLoad() * 100.0D, 0.0D) : 0.0D;

        this.players = Bukkit.getOnlinePlayers().size();
        int chunkCount = 0;
        int entityCount = 0;
        for (World world : Bukkit.getWorlds()) {
            chunkCount += world.getLoadedChunks().length;
            entityCount += world.getEntities().size();
        }
        this.chunks = chunkCount;
        this.entities = entityCount;

        super.run();
    }

    private float getBossBarProgress() {
        if (PurpurConfig.commandTPSBarProgressFillMode == FillMode.MSPT) {
            return Math.max(Math.min((float) mspt / 50.0F, 1.0F), 0.0F);
        } else {
            return Math.max(Math.min((float) tps / 20.0F, 1.0F), 0.0F);
        }
    }

    private BossBar.Color getBossBarColor() {
        if (isGood(PurpurConfig.commandTPSBarProgressFillMode)) {
            return PurpurConfig.commandTPSBarProgressColorGood;
        } else if (isMedium(PurpurConfig.commandTPSBarProgressFillMode)) {
            return PurpurConfig.commandTPSBarProgressColorMedium;
        } else {
            return PurpurConfig.commandTPSBarProgressColorLow;
        }
    }

    private boolean isGood(FillMode mode) {
        return isGood(mode, 0);
    }

    private boolean isGood(FillMode mode, int ping) {
        return isGood(mode, ping, this.tps);
    }

    private boolean isGood(FillMode mode, int ping, double tps) {
        if (mode == FillMode.MSPT) {
            return mspt < 40;
        } else if (mode == FillMode.TPS) {
            return tps >= 19;
        } else if (mode == FillMode.PING) {
            return ping < 100;
        } else {
            return false;
        }
    }

    private boolean isMedium(FillMode mode) {
        return isMedium(mode, 0);
    }

    private boolean isMedium(FillMode mode, int ping) {
        return isMedium(mode, ping, this.tps);
    }

    private boolean isMedium(FillMode mode, int ping, double tps) {
        if (mode == FillMode.MSPT) {
            return mspt < 50;
        } else if (mode == FillMode.TPS) {
            return tps >= 15;
        } else if (mode == FillMode.PING) {
            return ping < 200;
        } else {
            return false;
        }
    }

    private Component getTPSColor() {
        return getTPSColor(this.tps);
    }

    private Component getTPSColor(double tps) {
        String color;
        if (isGood(FillMode.TPS, 0, tps)) {
            color = PurpurConfig.commandTPSBarTextColorGood;
        } else if (isMedium(FillMode.TPS, 0, tps)) {
            color = PurpurConfig.commandTPSBarTextColorMedium;
        } else {
            color = PurpurConfig.commandTPSBarTextColorLow;
        }
        return MiniMessage.miniMessage().deserialize(color, Placeholder.parsed("text", String.format("%.2f", tps)));
    }

    private Component getMSPTColor() {
        String color;
        if (isGood(FillMode.MSPT)) {
            color = PurpurConfig.commandTPSBarTextColorGood;
        } else if (isMedium(FillMode.MSPT)) {
            color = PurpurConfig.commandTPSBarTextColorMedium;
        } else {
            color = PurpurConfig.commandTPSBarTextColorLow;
        }
        return MiniMessage.miniMessage().deserialize(color, Placeholder.parsed("text", String.format("%.2f", mspt)));
    }

    private Component getPingColor(int ping) {
        String color;
        if (isGood(FillMode.PING, ping)) {
            color = PurpurConfig.commandTPSBarTextColorGood;
        } else if (isMedium(FillMode.PING, ping)) {
            color = PurpurConfig.commandTPSBarTextColorMedium;
        } else {
            color = PurpurConfig.commandTPSBarTextColorLow;
        }
        return MiniMessage.miniMessage().deserialize(color, Placeholder.parsed("text", String.format("%s", ping)));
    }

    public enum FillMode {
        TPS, MSPT, PING
    }
}
