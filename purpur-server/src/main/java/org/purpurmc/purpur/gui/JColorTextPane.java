package org.purpurmc.purpur.gui;

import com.google.common.collect.Sets;
import javax.swing.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import java.util.Set;

public class JColorTextPane extends JTextPane {
    private static final GUIColor DEFAULT_COLOR;
    static {
        DEFAULT_COLOR = UIManager.getSystemLookAndFeelClassName().equals("com.sun.java.swing.plaf.gtk.GTKLookAndFeel") ? GUIColor.WHITE : GUIColor.BLACK;
    }

    public JColorTextPane(){
        new Timer(500, _ -> {
            SYNC_BLINK = !SYNC_BLINK;
            BLINKS.forEach(this::blink);
        }).start();
    }


    public void append(String msg) {
        Component component = LegacyComponentSerializer.legacySection().deserialize(DEFAULT_COLOR.getCode() + msg);
        append(component, Style.empty());
    }

    private void append(Component component, Style parentStyle) {
        Style style = parentStyle.merge(component.style());

        for (Component child : component.children()) {
            append(child, style);
        }
    }

    private void appendText(String text, Style style) {
        GUIColor guiColor = GUIColor.getColor(style.color());
        if (guiColor == null) {
            guiColor = DEFAULT_COLOR;
        }

        StyleContext context = StyleContext.getDefaultStyleContext();
        AttributeSet attr = context.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, guiColor.getColor());
        attr = context.addAttribute(attr, StyleConstants.CharacterConstants.Bold,
            style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE || guiColor != DEFAULT_COLOR);
        attr = context.addAttribute(attr, StyleConstants.CharacterConstants.Italic,
            style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE);
        attr = context.addAttribute(attr, StyleConstants.CharacterConstants.Underline,
            style.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE);
        attr = context.addAttribute(attr, StyleConstants.CharacterConstants.StrikeThrough,
            style.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE);

        try {
            int pos = getDocument().getLength();
            getDocument().insertString(pos, text, attr);

            if (style.decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE) {
                Blink blink = new Blink(pos, text.length(), attr,
                    context.addAttribute(attr, StyleConstants.Foreground, getBackground()));
                BLINKS.add(blink);
            }
        } catch (BadLocationException exception) {
            exception.printStackTrace(System.err);
        }
    }

    private static final Set<Blink> BLINKS = Sets.newHashSet();
    private static boolean SYNC_BLINK;

    public void blink(Blink blink) {
        getStyledDocument().setCharacterAttributes(blink.start(), blink.length(), SYNC_BLINK ? blink.attr() : blink.attr2(), true);
    }

    public record Blink(int start, int length, AttributeSet attr, AttributeSet attr2) {}
}
