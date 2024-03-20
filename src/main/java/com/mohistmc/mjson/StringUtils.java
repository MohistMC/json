/*
 * $Id$
 */

package com.mohistmc.mjson;

/**
 * @author Satyendra Gurjar
 */
public abstract class StringUtils {
    public static final String EMPTY_STR = "";

    public static String trimToNull(String str) {
        if (str == null) {
            return null;
        }

        str = str.trim();

        return (str.length() == 0) ? null : str;
    }
}
