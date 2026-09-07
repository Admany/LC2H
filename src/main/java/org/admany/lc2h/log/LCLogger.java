package org.admany.lc2h.log;

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
            LC2H.LOGGER.debug(line);
        }
    }

    public static void configure(boolean routineLoggingEnabled, boolean debugLoggingEnabled) {
        String levelName = !routineLoggingEnabled
            ? "WARN"
            : (debugLoggingEnabled ? "DEBUG" : "INFO");
        try {
            Class<?> configurator = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Object level = levelClass.getField(levelName).get(null);
            configurator.getMethod("setLevel", String.class, levelClass)
                .invoke(null, "org.admany.lc2h", level);
        } catch (Throwable ignored) {
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
