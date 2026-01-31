package com.mohistmc.mjson;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class NullJson extends Json {

    NullJson() {
    }

    NullJson(Json e) {
        super(e);
    }

    public Object getValue() {
        return null;
    }

    public Json dup() {
        return new NullJson();
    }

    public boolean isNull() {
        return true;
    }

    public String toString() {
        return "null";
    }

    public List<Object> asList() {
        return Collections.singletonList(null);
    }

    public int hashCode() {
        return 0;
    }

    public boolean equals(Object x) {
        return x instanceof NullJson;
    }

    @Override
    public Iterator<Json> iterator() {
        return new JsonSingleValueIterator() {
            @Override
            public Json next() {
                super.next();
                return NullJson.this;
            }
        };
    }
}
