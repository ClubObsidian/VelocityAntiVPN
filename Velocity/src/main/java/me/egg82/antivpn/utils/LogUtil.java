package me.egg82.antivpn.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

public class LogUtil {
    private LogUtil() {}

    public static TextComponent getHeading() {
        return Component.text("[").color(NamedTextColor.YELLOW)
                .append(Component.text("AntiVPN").color(NamedTextColor.AQUA))
                .append(Component.text("] ").color(NamedTextColor.YELLOW));
    }
}
