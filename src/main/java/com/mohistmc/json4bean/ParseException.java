package com.mohistmc.json4bean;

import java.io.Serial;
import lombok.Getter;

/**
 * The JSONException is thrown when deserialize an illegal json.
 *
 * @author Fan Wen Jie
 * @version 2015-03-05
 */
@Getter
public class ParseException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 3674125742687171239L;
    private final int position;
    private final String json;

    /**
     * Constructs a new json exception with the specified detail message.
     * The cause is not initialized, and may subsequently be initialized by a
     * call to {@link #initCause}.
     *
     * @param json     the json text which cause JSONParseException
     * @param position the position of illegal escape char at json text;
     * @param message  the detail message. The detail message is saved for
     *                 later retrieval by the {@link #getMessage()} method.
     */
    public ParseException(String json, int position, String message) {
        super(message);
        this.json = json;
        this.position = position;
    }

    /**
     * Get message about error when parsing illegal json
     *
     * @return error message
     */
    @Override
    public String getMessage() {
        final int maxTipLength = 10;
        int end = position + 1;
        int start = end - maxTipLength;
        if (start < 0) start = 0;
        if (end > json.length()) end = json.length();
        return String.format("%s  (%d):%s", json.substring(start, end), position, super.getMessage());
    }

}
