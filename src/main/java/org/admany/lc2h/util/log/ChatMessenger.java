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

    private ChatMessenger() {
    }

    private static MutableComponent prefix() {
        return Component.literal("[LC2H] ").withStyle(style -> style.withColor(COLOR_PREFIX).withBold(true));
    }

    private static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
    }

    private static MutableComponent prefixed(String text, int color) {
        return prefix().append(colored(text, color));
    }

    public static void info(CommandSourceStack source, String message) {
        source.sendSuccess(() -> prefixed(message, COLOR_PRIMARY), false);
    }

    public static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> prefixed(message, COLOR_SUCCESS), false);
    }

    public static void warn(CommandSourceStack source, String message) {
        source.sendSuccess(() -> prefixed(message, COLOR_WARN), false);
    }

    public static void error(CommandSourceStack source, String message) {
        source.sendFailure(prefixed(message, COLOR_ERROR));
    }
}
