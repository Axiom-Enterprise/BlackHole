package org.purpurmc.purpur.gui;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public enum GUIColor {

    BLACK(NamedTextColor.BLACK, '0', new Color(0x000000)),
    DARK_BLUE(NamedTextColor.DARK_BLUE, '1', new Color(0x0000AA)),
    DARK_GREEN(NamedTextColor.DARK_GREEN, '2', new Color(0x00AA00)),
    DARK_AQUA(NamedTextColor.DARK_AQUA, '3', new Color(0x009999)),
    DARK_RED(NamedTextColor.DARK_RED, '4', new Color(0xAA0000)),
    DARK_PURPLE(NamedTextColor.DARK_PURPLE, '5', new Color(0xAA00AA)),
    GOLD(NamedTextColor.GOLD, '6', new Color(0xBB8800)),
    GRAY(NamedTextColor.GRAY, '7', new Color(0x888888)),
    DARK_GRAY(NamedTextColor.DARK_GRAY, '8', new Color(0x444444)),
    BLUE(NamedTextColor.BLUE, '9', new Color(0x5555FF)),
    GREEN(NamedTextColor.GREEN, 'a', new Color(0x55FF55)),
    AQUA(NamedTextColor.AQUA, 'b', new Color(0x55DDDD)),
    RED(NamedTextColor.RED, 'c', new Color(0xFF5555)),
    LIGHT_PURPLE(NamedTextColor.LIGHT_PURPLE, 'd', new Color(0xFF55FF)),
    YELLOW(NamedTextColor.YELLOW, 'e', new Color(0xFFBB00)),
    WHITE(NamedTextColor.WHITE, 'f', new Color(0xBBBBBB));

    private final NamedTextColor textColor;
    private final char legacyCode;
    private final Color color;

    private static final Map<NamedTextColor, GUIColor> BY_TEXT_COLOR = new HashMap<>();

    GUIColor(NamedTextColor textColor, char legacyCode, Color color) {
        this.textColor = textColor;
        this.legacyCode = legacyCode;
        this.color = color;
    }

    public Color getColor() {
        return color;
    }

    public NamedTextColor getTextColor() {
        return textColor;
    }

    public String getCode() {
        return "§" + legacyCode;
    }

    public static GUIColor getColor(TextColor textColor) {
        if (textColor instanceof NamedTextColor namedTextColor) {
            return BY_TEXT_COLOR.get(namedTextColor);
        }

        return null;
    }

    static {
        for (GUIColor color : values()) {
            BY_TEXT_COLOR.put(color.textColor, color);
        }
    }
}
