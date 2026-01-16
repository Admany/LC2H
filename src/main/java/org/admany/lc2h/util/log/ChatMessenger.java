package org.admany.lc2h.util.log;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;


public final class ChatMessenger {

    private static final int COLOR_PREFIX = 0x2CB0FF; 
    private static final int COLOR_PRIMARY = 0x8CD6FF;
    private static final int COLOR_SUCCESS = 0x7CFC89;
    private static final int COLOR_WARN = 0xFFD166;
    private static final int COLOR_ERROR = 0xFF6F6A;
    private static final String PREFIX_KEY = "lc2h.chat.prefix";

    private ChatMessenger() {
    }

    public static MutableComponent prefixComponent() {
        return Component.translatable(PREFIX_KEY).withStyle(style -> style.withColor(COLOR_PREFIX).withBold(true));
    }

    private static MutableComponent colored(Component message, int color) {
        return Component.empty().append(message).withStyle(Style.EMPTY.withColor(color));
    }

    public static MutableComponent prefixedComponent(Component message, int color) {
        return prefixComponent().append(colored(message, color));
    }

    public static void info(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> prefixedComponent(message, COLOR_PRIMARY), false);
    }

    public static void info(CommandSourceStack source, String message) {
        info(source, Component.literal(message));
    }

    public static void success(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> prefixedComponent(message, COLOR_SUCCESS), false);
    }

    public static void success(CommandSourceStack source, String message) {
        success(source, Component.literal(message));
    }

    public static void warn(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> prefixedComponent(message, COLOR_WARN), false);
    }

    public static void warn(CommandSourceStack source, String message) {
        warn(source, Component.literal(message));
    }

    public static void error(CommandSourceStack source, Component message) {
        source.sendFailure(prefixedComponent(message, COLOR_ERROR));
    }

    public static void error(CommandSourceStack source, String message) {
        error(source, Component.literal(message));
    }
}
