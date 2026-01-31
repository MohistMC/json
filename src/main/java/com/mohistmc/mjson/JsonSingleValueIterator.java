package com.mohistmc.mjson;

import java.util.Iterator;

/**
 * @author Mgazul
 * @date 2026/2/1 03:16
 */
public class JsonSingleValueIterator implements Iterator<Json> {
    private boolean retrieved = false;
    @Override
    public boolean hasNext() {
        return !retrieved;
    }

    @Override
    public Json next() {
        retrieved = true;
        return null;
    }

    @Override
    public void remove() {
    }
}
