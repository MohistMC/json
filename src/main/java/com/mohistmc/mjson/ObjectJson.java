package com.mohistmc.mjson;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

public class ObjectJson extends Json {

    final Map<String, Json> object = new LinkedHashMap<>();

    ObjectJson() {
    }

    ObjectJson(Json e) {
        super(e);
    }

    public Json dup() {
        ObjectJson j = new ObjectJson();
        for (Map.Entry<String, Json> e : object.entrySet()) {
            Json v = e.getValue().dup();
            v.enclosing = j;
            j.object.put(e.getKey(), v);
        }
        return j;
    }

    public boolean has(String property) {
        return object.containsKey(property);
    }

    public boolean is(String property, Object value) {
        Json p = object.get(property);
        if (p == null)
            return false;
        else
            return p.equals(make(value));
    }

    public Json at(String property) {
        return object.get(property);
    }

    // Mohist start
    public boolean asBoolean(String property) {
        return at(property).asBoolean();
    }

    public String asString(String property) {
        return at(property).asString();
    }

    public int asInteger(String property) {
        return at(property).asInteger();
    }

    public float asFloat(String property) {
        return at(property).asFloat();
    }

    public double asDouble(String property) {
        return at(property).asDouble();
    }

    public long asLong(String property) {
        return at(property).asLong();
    }

    public short asShort(String property) {
        return at(property).asShort();
    }

    public byte asByte(String property) {
        return at(property).asByte();
    }

    public char asChar(String property) {
        return at(property).asChar();
    }

    public Map<String, Object> asMap(String property) {
        return at(property).asMap();
    }

    public Map<String, Json> asJsonMap(String property) {
        return at(property).asJsonMap();
    }

    public List<Object> asList(String property) {
        return at(property).asList();
    }

    public List<Json> asJsonList(String property) {
        return at(property).asJsonList();
    }

    public <T> T asBean(Class<T> classZ) {
        return BeanSerializer.deserialize(classZ, JSONSerializer.deserialize(this.toString()));
    }

    public byte[] asBytes() {
        return this.toString().getBytes(StandardCharsets.UTF_8);
    }

    public Properties asProperties() {
        Properties properties = new Properties();
        if (this != null) {
            // Don't use the new entrySet API to maintain Android support
            for (Entry<String, Object> mapEntry : asMap().entrySet()) {
                Object value = mapEntry.getValue();
                if (!(value instanceof Json)) {
                    properties.put(mapEntry.getKey(), value.toString());
                }
            }
        }
        return properties;
    }
    // Mohist end

    protected Json withOptions(Json other, Json allOptions, String path) {
        if (!allOptions.has(path))
            allOptions.set(path, object());
        Json options = allOptions.at(path, object());
        boolean duplicate = options.is("dup", true);
        if (options.is("merge", true)) {
            for (Map.Entry<String, Json> e : other.asJsonMap().entrySet()) {
                Json local = object.get(e.getKey());
                if (local instanceof ObjectJson)
                    ((ObjectJson) local).withOptions(e.getValue(), allOptions, path + "/" + e.getKey());
                else if (local instanceof ArrayJson)
                    ((ArrayJson) local).withOptions(e.getValue(), allOptions, path + "/" + e.getKey());
                else
                    set(e.getKey(), duplicate ? e.getValue().dup() : e.getValue());
            }
        } else if (duplicate)
            for (Map.Entry<String, Json> e : other.asJsonMap().entrySet())
                set(e.getKey(), e.getValue().dup());
        else
            for (Map.Entry<String, Json> e : other.asJsonMap().entrySet())
                set(e.getKey(), e.getValue());
        return this;
    }

    public Json with(Json x, Json... options) {
        if (x == null) return this;
        if (!x.isObject())
            throw new UnsupportedOperationException();
        if (options.length > 0) {
            Json O = collectWithOptions(options);
            return withOptions(x, O, "");
        } else for (Map.Entry<String, Json> e : x.asJsonMap().entrySet())
            set(e.getKey(), e.getValue());
        return this;
    }

    public Json set(String property, Json el) {
        if (property == null)
            throw new IllegalArgumentException("Null property names are not allowed, value is " + el);
        if (el == null)
            el = nil();
        setParent(el, this);
        object.put(property, el);
        return this;
    }

    public Json atDel(String property) {
        Json el = object.remove(property);
        removeParent(el, this);
        return el;
    }

    public Json delAt(String property) {
        Json el = object.remove(property);
        removeParent(el, this);
        return this;
    }

    public Object getValue() {
        return asMap();
    }

    public boolean isObject() {
        return true;
    }

    public Map<String, Object> asMap() {
        HashMap<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Json> e : object.entrySet())
            m.put(e.getKey(), e.getValue().getValue());
        return m;
    }

    @Override
    public Map<String, Json> asJsonMap() {
        return object;
    }

    public String toString() {
        return toString(Integer.MAX_VALUE);
    }

    public String toString(int maxCharacters) {
        return toStringImpl(maxCharacters, new IdentityHashMap<>());
    }

    String toStringImpl(int maxCharacters, Map<Json, Json> done) {
        StringBuilder sb = new StringBuilder("{");
        if (done.containsKey(this))
            return sb.append("...}").toString();
        done.put(this, this);
        for (Iterator<Entry<String, Json>> i = object.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<String, Json> x = i.next();
            sb.append('"');
            sb.append(escaper.escapeJsonString(x.getKey()));
            sb.append('"');
            sb.append(":");
            String s = x.getValue().isObject() ? ((ObjectJson) x.getValue()).toStringImpl(maxCharacters, done)
                    : x.getValue().isArray() ? ((ArrayJson) x.getValue()).toStringImpl(maxCharacters, done)
                    : x.getValue().toString(maxCharacters);
            if (sb.length() + s.length() > maxCharacters)
                s = s.substring(0, Math.max(0, maxCharacters - sb.length()));
            sb.append(s);
            if (i.hasNext())
                sb.append(",");
            if (sb.length() >= maxCharacters) {
                sb.append("...");
                break;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    public int hashCode() {
        return object.hashCode();
    }

    public boolean equals(Object x) {
        return x instanceof ObjectJson && ((ObjectJson) x).object.equals(object);
    }
}
