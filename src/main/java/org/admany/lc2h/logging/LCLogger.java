package org.admany.lc2h.logging;

import org.admany.lc2h.LC2H;

public class LCLogger {
    private static final String PREFIX = "[LC2H] ";

    private static final String[] LOGO = new String[] {
        "      :::        ::::::::   ::::::::  :::    ::: ",
        "     :+:       :+:    :+: :+:    :+: :+:    :+:  ",
        "    +:+       +:+              +:+  +:+    +:+   ",
        "   +#+       +#+            +#+    +#++:++#++    ",
        "  +#+       +#+          +#+      +#+    +#+     ",
        " #+#       #+#    #+#  #+#       #+#    #+#      ",
        "########## ########  ########## ###    ###       "
    };

    static {
        for (String line : LOGO) {
            LC2H.LOGGER.info(line);
        }
    }

    private static String ensurePrefix(String message) {
        if (message.startsWith(PREFIX)) {
            return message;
        }
        return PREFIX + message;
    }

    public static void info(String message) {
        LC2H.LOGGER.info(ensurePrefix(message));
    }

    public static void info(String message, Object... args) {
        LC2H.LOGGER.info(ensurePrefix(message), args);
    }

    public static void warn(String message) {
        LC2H.LOGGER.warn(ensurePrefix(message));
    }

    public static void warn(String message, Object... args) {
        LC2H.LOGGER.warn(ensurePrefix(message), args);
    }

    public static void error(String message) {
        LC2H.LOGGER.error(ensurePrefix(message));
    }

    public static void error(String message, Throwable throwable) {
        LC2H.LOGGER.error(ensurePrefix(message), throwable);
    }

    public static void warn(String message, Throwable throwable) {
        LC2H.LOGGER.warn(ensurePrefix(message), throwable);
    }

    public static void debug(String message) {
        LC2H.LOGGER.debug(ensurePrefix(message));
    }

    public static void debug(String message, Object... args) {
        LC2H.LOGGER.debug(ensurePrefix(message), args);
    }
}