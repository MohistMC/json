/*
 * Copyright (C) 2011 Miami-Dade County.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Note: this file incorporates source code from 3d party entities. Such code
 * is copyrighted by those entities as indicated below.
 */
package com.mohistmc.mjson;

import java.io.IOException;
import java.io.Serial;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Setter;
import org.xml.sax.InputSource;

public class Json implements java.io.Serializable {
    public static final Factory defaultFactory = new DefaultFactory();
    @Serial
    private static final long serialVersionUID = 1L;
    // TODO: maybe use initialValue thread-local method to attach global factory by default here...
    private static final ThreadLocal<Factory> threadFactory = new ThreadLocal<>();
    /**
     * A utility class that is used to perform JSON escaping so that ", <, >, etc. characters are
     * properly encoded in the JSON string representation before returning to the client code.
     *
     * <p>This class contains a single method to escape a passed in string value:
     * <pre>
     *   String jsonStringValue = "beforeQuote\"afterQuote";
     *   String escapedValue = Escaper.escapeJsonString(jsonStringValue);
     * </pre></p>
     *
     * @author Inderjeet Singh
     * @author Joel Leitch
     */
    static Escaper escaper = new Escaper(false);
    /**
     * -- SETTER --
     * <p>
     * Specify a global Json
     * to be used by all threads that don't have a
     * specific thread-local factory attached to them.
     * </p>
     *
     * @param factory The new global factory
     */
    @Setter
    private static Factory globalFactory = defaultFactory;
    Json enclosing = null;

    protected Json() {
    }

    protected Json(Json enclosing) {
        this.enclosing = enclosing;
    }

    static String fetchContent(URL url) {
        java.io.Reader reader = null;
        try {
            reader = new java.io.InputStreamReader((java.io.InputStream) url.getContent());
            StringBuilder content = new StringBuilder();
            char[] buf = new char[1024];
            for (int n = reader.read(buf); n > -1; n = reader.read(buf))
                content.append(buf, 0, n);
            return content.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            if (reader != null) try {
                reader.close();
            } catch (Throwable t) {
            }
        }
    }

    static Json resolvePointer(String pointerRepresentation, Json top) {
        String[] parts = pointerRepresentation.split("/");
        Json result = top;
        for (String p : parts) {
            // TODO: unescaping and decoding
            if (p.isEmpty())
                continue;
            p = p.replace("~1", "/").replace("~0", "~");
            if (result.isArray())
                result = result.at(Integer.parseInt(p));
            else if (result.isObject())
                result = result.at(p);
            else
                throw new RuntimeException("Can't resolve pointer " + pointerRepresentation +
                        " on document " + top.toString(200));
        }
        return result;
    }

    static URI makeAbsolute(URI base, String ref) throws Exception {
        URI refuri;
        if (base != null && base.getAuthority() != null && !new URI(ref).isAbsolute()) {
            StringBuilder sb = new StringBuilder();
            if (base.getScheme() != null)
                sb.append(base.getScheme()).append("://");
            sb.append(base.getAuthority());
            if (!ref.startsWith("/")) {
                if (ref.startsWith("#"))
                    sb.append(base.getPath());
                else {
                    int slashIdx = base.getPath().lastIndexOf('/');
                    sb.append(slashIdx == -1 ? base.getPath() : base.getPath().substring(0, slashIdx)).append("/");
                }
            }
            refuri = new URI(sb.append(ref).toString());
        } else if (base != null)
            refuri = base.resolve(ref);
        else
            refuri = new URI(ref);
        return refuri;
    }

    static Json resolveRef(URI base,
                           Json refdoc,
                           URI refuri,
                           Map<String, Json> resolved,
                           Map<Json, Json> expanded,
                           Function<URI, Json> uriResolver) throws Exception {
        if (refuri.isAbsolute() &&
                (base == null || !base.isAbsolute() ||
                        !base.getScheme().equals(refuri.getScheme()) ||
                        !Objects.equals(base.getHost(), refuri.getHost()) ||
                        base.getPort() != refuri.getPort() ||
                        !base.getPath().equals(refuri.getPath()))) {
            URI docuri;
            refuri = refuri.normalize();
            if (refuri.getHost() == null)
                docuri = new URI(refuri.getScheme() + ":" + refuri.getPath());
            else
                docuri = new URI(refuri.getScheme() + "://" + refuri.getHost() +
                        ((refuri.getPort() > -1) ? ":" + refuri.getPort() : "") +
                        refuri.getPath());
            refdoc = uriResolver.apply(docuri);
            refdoc = expandReferences(refdoc, refdoc, docuri, resolved, expanded, uriResolver);
        }
        if (refuri.getFragment() == null)
            return refdoc;
        else
            return resolvePointer(refuri.getFragment(), refdoc);
    }

    /**
     * <p>
     * Replace all JSON references, as per the http://tools.ietf.org/html/draft-pbryan-zyp-json-ref-03
     * specification, by their referants.
     * </p>
     *
     * @param json
     * @param duplicate
     * @param done
     * @return
     */
    static Json expandReferences(Json json,
                                 Json topdoc,
                                 URI base,
                                 Map<String, Json> resolved,
                                 Map<Json, Json> expanded,
                                 Function<URI, Json> uriResolver) throws Exception {
        if (expanded.containsKey(json)) return json;
        if (json.isObject()) {
            if (json.has("id") && json.at("id").isString()) // change scope of nest references
            {
                base = base.resolve(json.at("id").asString());
            }

            if (json.has("$ref")) {
                URI refuri = makeAbsolute(base, json.at("$ref").asString()); // base.resolve(json.at("$ref").asString());
                Json ref = resolved.get(refuri.toString());
                if (ref == null) {
                    ref = Json.object();
                    resolved.put(refuri.toString(), ref);
                    ref.with(resolveRef(base, topdoc, refuri, resolved, expanded, uriResolver));
                }
                json = ref;
            } else {
                for (Map.Entry<String, Json> e : json.asJsonMap().entrySet())
                    json.set(e.getKey(), expandReferences(e.getValue(), topdoc, base, resolved, expanded, uriResolver));
            }
        } else if (json.isArray()) {
            for (int i = 0; i < json.asJsonList().size(); i++)
                json.set(i,
                        expandReferences(json.at(i), topdoc, base, resolved, expanded, uriResolver));
        }
        expanded.put(json, json);
        return json;
    }

    public static Schema schema(Json S) {
        return new DefaultSchema(null, S, null);
    }

    public static Schema schema(URI uri) {
        return schema(uri, null);
    }

    public static Schema schema(URI uri, Function<URI, Json> relativeReferenceResolver) {
        try {
            return new DefaultSchema(uri, Json.read(Json.fetchContent(uri.toURL())), relativeReferenceResolver);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Schema schema(Json S, URI uri) {
        return new DefaultSchema(uri, S, null);
    }

    /**
     * <p>Return the {@link Factory} currently in effect. This is the factory that the {@link #make(Object)} method
     * will dispatch on upon determining the type of its argument. If you already know the type
     * of element to construct, you can avoid the type introspection implicit to the make method
     * and call the factory directly. This will result in an optimization. </p>
     *
     * @return the factory
     */
    public static Factory factory() {
        Factory f = threadFactory.get();
        return f != null ? f : globalFactory;
    }

    /**
     * <p>
     * Attach a thread-local Json {@link Factory} to be used specifically by this thread. Thread-local
     * Json factories are the only means to have different {@link Factory} implementations used simultaneously
     * in the same application (well, more accurately, the same ClassLoader).
     * </p>
     *
     * @param factory the new thread local factory
     */
    public static void attachFactory(Factory factory) {
        threadFactory.set(factory);
    }

    /**
     * <p>
     * Clear the thread-local factory previously attached to this thread via the
     * {@link #attachFactory(Factory)} method. The global factory takes effect after
     * a call to this method.
     * </p>
     */
    public static void detachFactory() {
        threadFactory.remove();
    }

    /**
     * <p>
     * Parse a JSON entity from its string representation.
     * </p>
     *
     * @param jsonAsString A valid JSON representation as per the <a href="http://www.json.org">json.org</a>
     *                     grammar. Cannot be <code>null</code>.
     * @return The JSON entity parsed: an object, array, string, number or boolean, or null. Note that
     * this method will never return the actual Java <code>null</code>.
     */
    public static Json read(String jsonAsString) {
        return (Json) new Reader().read(jsonAsString);
    }

    public static Json readXml(String jsonAsString) {
        return Json.factory().make(XmlUtils.xmlToMap(new InputSource(jsonAsString)));
    }

    /**
     * <p>
     * Parse a JSON entity from a <code>URL</code>.
     * </p>
     *
     * @param location A valid URL where to load a JSON document from. Cannot be <code>null</code>.
     * @return The JSON entity parsed: an object, array, string, number or boolean, or null. Note that
     * this method will never return the actual Java <code>null</code>.
     */
    public static Json read(URL location) {
        return (Json) new Reader().read(fetchContent(location));
    }

    /**
     * 从指定的URL位置读取XML内容，并将其转换为Json格式。
     *
     * @param url XML文件的位置，需要是一个有效的URL。
     * @return 从XML转换得到的Json对象。
     */
    public static Json readXml(URL url) {
        return readXml(url.toString());
    }

    public static Json read(Object object) {
        return Json.factory().make(object);
    }

    public static Json readBean(Object clazz) {
        return read(JSONSerializer.serialize(BeanSerializer.serialize(clazz)));
    }

    public static Json read(Properties properties) {
        var jo = Json.object();
        if (properties != null && !properties.isEmpty()) {
            Enumeration<?> enumProperties = properties.propertyNames();
            while (enumProperties.hasMoreElements()) {
                String name = (String) enumProperties.nextElement();
                jo.set(name, properties.getProperty(name));
            }
        }
        return jo;
    }

    /**
     * <p>
     * Parse a JSON entity from a {@link CharacterIterator}.
     * </p>
     *
     * @param it A character iterator.
     * @return the parsed JSON element
     * @see #read(String)
     */
    public static Json read(CharacterIterator it) {
        return (Json) new Reader().read(it);
    }

    /**
     * @return the <code>null Json</code> instance.
     */
    public static Json nil() {
        return factory().nil();
    }

    /**
     * @return a newly constructed, empty JSON object.
     */
    public static Json object() {
        return factory().object();
    }

    /**
     * <p>Return a new JSON object initialized from the passed list of
     * name/value pairs. The number of arguments must
     * be even. Each argument at an even position is taken to be a name
     * for the following value. The name arguments are normally of type
     * Java String, but they can be of any other type having an appropriate
     * <code>toString</code> method. Each value is first converted
     * to a <code>Json</code> instance using the {@link #make(Object)} method.
     * </p>
     *
     * @param args A sequence of name value pairs.
     * @return the new JSON object.
     */
    public static Json object(Object... args) {
        Json j = object();
        if (args.length % 2 != 0)
            throw new IllegalArgumentException("An even number of arguments is expected.");
        for (int i = 0; i < args.length; i++)
            j.set(args[i].toString(), factory().make(args[++i]));
        return j;
    }

    /**
     * @return a new constructed, empty JSON array.
     */
    public static Json array() {
        return factory().array();
    }

    /**
     * <p>Return a new JSON array filled up with the list of arguments.</p>
     *
     * @param args The initial content of the array.
     * @return the new JSON array
     */
    public static Json array(Object... args) {
        Json A = array();
        for (Object x : args)
            A.add(factory().make(x));
        return A;
    }

    /**
     * <p>
     * Convert an arbitrary Java instance to a {@link Json} instance.
     * </p>
     *
     * <p>
     * Maps, Collections and arrays are recursively copied where each of
     * their elements concerted into <code>Json</code> instances as well. The keys
     * of a {@link Map} parameter are normally strings, but anything with a meaningful
     * <code>toString</code> implementation will work as well.
     * </p>
     *
     * @param anything Any Java object that the current JSON factory in effect is capable of handling.
     * @return The <code>Json</code>. This method will never return <code>null</code>. It will
     * throw an {@link IllegalArgumentException} if it doesn't know how to convert the argument
     * to a <code>Json</code> instance.
     * @throws IllegalArgumentException when the concrete type of the parameter is
     *                                  unknown.
     */
    public static Json make(Object anything) {
        return factory().make(anything);
    }

    /**
     * <p>
     * Set the parent (i.e. enclosing element) of Json element.
     * </p>
     *
     * @param el
     * @param parent
     */
    static void setParent(Json el, Json parent) {
        if (el.enclosing == null)
            el.enclosing = parent;
        else if (el.enclosing instanceof ParentArrayJson)
            ((ParentArrayJson) el.enclosing).L.add(parent);
        else {
            ParentArrayJson A = new ParentArrayJson();
            A.L.add(el.enclosing);
            A.L.add(parent);
            el.enclosing = A;
        }
    }

    /**
     * <p>
     * Remove/unset the parent (i.e. enclosing element) of Json element.
     * </p>
     *
     * @param el
     * @param parent
     */
    static void removeParent(Json el, Json parent) {
        if (el.enclosing == parent)
            el.enclosing = null;
        else if (el.enclosing.isArray()) {
            ArrayJson A = (ArrayJson) el.enclosing;
            int idx = 0;
            while (A.L.get(idx) != parent) idx++;
            if (idx < A.L.size())
                A.L.remove(idx);
        }
    }

    // end of static utility method section

    /**
     * <p>Return a string representation of <code>this</code> that does
     * not exceed a certain maximum length. This is useful in constructing
     * error messages or any other place where only a "preview" of the
     * JSON element should be displayed. Some JSON structures can get
     * very large and this method will help avoid string serializing
     * the whole of them. </p>
     *
     * @param maxCharacters The maximum number of characters for
     *                      the string representation.
     * @return The string representation of this object.
     */
    public String toString(int maxCharacters) {
        return toString();
    }

    /**
     * <p>Explicitly set the parent of this element. The parent is presumably an array
     * or an object. Normally, there's no need to call this method as the parent is
     * automatically set by the framework. You may need to call it however, if you implement
     * your own {@link Factory} with your own implementations of the Json types.
     * </p>
     *
     * @param enclosing The parent element.
     */
    public void attachTo(Json enclosing) {
        this.enclosing = enclosing;
    }

    /**
     * @return the <code>Json</code> entity, if any, enclosing this
     * <code>Json</code>. The returned value can be <code>null</code> or
     * a <code>Json</code> object or list, but not one of the primitive types.
     */
    public final Json up() {
        return enclosing;
    }

    /**
     * @return a clone (a duplicate) of this <code>Json</code> entity. Note that cloning
     * is deep if array and objects. Primitives are also cloned, even though their values are immutable
     * because the new enclosing entity (the result of the {@link #up()} method) may be different.
     * since they are immutable.
     */
    public Json dup() {
        return this;
    }

    /**
     * <p>Return the <code>Json</code> element at the specified index of this
     * <code>Json</code> array. This method applies only to Json arrays.
     * </p>
     *
     * @param index The index of the desired element.
     * @return The JSON element at the specified index in this array.
     */
    public Json at(int index) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Return the specified property of a <code>Json</code> object or <code>null</code>
     * if there's no such property. This method applies only to Json objects.
     * </p>
     *
     * @param property The property name.
     * @return The JSON element that is the value of that property.
     */
    public Json at(String property) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Return the specified property of a <code>Json</code> object if it exists.
     * If it doesn't, then create a new property with value the <code>def</code>
     * parameter and return that parameter.
     * </p>
     *
     * @param property The property to return.
     * @param def      The default value to set and return in case the property doesn't exist.
     * @return The JSON element that is the value of that property or the <code>def</code>
     * parameter if the value does not exist.
     */
    public final Json at(String property, Json def) {
        Json x = at(property);
        if (x == null) {
//			set(property, def);
            return def;
        } else
            return x;
    }

    /**
     * <p>
     * Return the specified property of a <code>Json</code> object if it exists.
     * If it doesn't, then create a new property with value the <code>def</code>
     * parameter and return that parameter.
     * </p>
     *
     * @param property The property to return.
     * @param def      The default value to set and return in case the property doesn't exist.
     * @return The JSON element that is the value of that property or <Code>def</code>.
     */
    public final Json at(String property, Object def) {
        return at(property, make(def));
    }

    /**
     * <p>
     * Return true if this <code>Json</code> object has the specified property
     * and false otherwise.
     * </p>
     *
     * @param property The name of the property.
     */
    public boolean has(String property) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Return <code>true</code> if and only if this <code>Json</code> object has a property with
     * the specified value. In particular, if the object has no such property <code>false</code> is returned.
     * </p>
     *
     * @param property The property name.
     * @param value    The value to compare with. Comparison is done via the equals method.
     *                 If the value is not an instance of <code>Json</code>, it is first converted to
     *                 such an instance.
     * @return
     */
    public boolean is(String property, Object value) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Return <code>true</code> if and only if this <code>Json</code> array has an element with
     * the specified value at the specified index. In particular, if the array has no element at
     * this index, <code>false</code> is returned.
     * </p>
     *
     * @param index The 0-based index of the element in a JSON array.
     * @param value The value to compare with. Comparison is done via the equals method.
     *              If the value is not an instance of <code>Json</code>, it is first converted to
     *              such an instance.
     * @return
     */
    public boolean is(int index, Object value) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Add the specified <code>Json</code> element to this array.
     * </p>
     *
     * @return this
     */
    public Json add(Json el) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Add an arbitrary Java object to this <code>Json</code> array. The object
     * is first converted to a <code>Json</code> instance by calling the static
     * {@link #make} method.
     * </p>
     *
     * @param anything Any Java object that can be converted to a Json instance.
     * @return this
     */
    public final Json add(Object anything) {
        return add(make(anything));
    }

    /**
     * <p>
     * Remove the specified property from a <code>Json</code> object and return
     * that property.
     * </p>
     *
     * @param property The property to be removed.
     * @return The property value or <code>null</code> if the object didn't have such
     * a property to begin with.
     */
    public Json atDel(String property) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Remove the element at the specified index from a <code>Json</code> array and return
     * that element.
     * </p>
     *
     * @param index The index of the element to delete.
     * @return The element value.
     */
    public Json atDel(int index) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Delete the specified property from a <code>Json</code> object.
     * </p>
     *
     * @param property The property to be removed.
     * @return this
     */
    public Json delAt(String property) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Remove the element at the specified index from a <code>Json</code> array.
     * </p>
     *
     * @param index The index of the element to delete.
     * @return this
     */
    public Json delAt(int index) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Remove the specified element from a <code>Json</code> array.
     * </p>
     *
     * @param el The element to delete.
     * @return this
     */
    public Json remove(Json el) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Remove the specified Java object (converted to a Json instance)
     * from a <code>Json</code> array. This is equivalent to
     * <code>remove({@link #make(Object)})</code>.
     * </p>
     *
     * @param anything The object to delete.
     * @return this
     */
    public final Json remove(Object anything) {
        return remove(make(anything));
    }

    /**
     * <p>
     * Set a <code>Json</code> objects's property.
     * </p>
     *
     * @param property The property name.
     * @param value    The value of the property.
     * @return this
     */
    public Json set(String property, Json value) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Set a <code>Json</code> objects's property.
     * </p>
     *
     * @param property The property name.
     * @param value    The value of the property, converted to a <code>Json</code> representation
     *                 with {@link #make}.
     * @return this
     */
    public final Json set(String property, Object value) {
        return set(property, make(value));
    }

    /**
     * <p>
     * Change the value of a JSON array element. This must be an array.
     * </p>
     *
     * @param index 0-based index of the element in the array.
     * @param value the new value of the element
     * @return this
     */
    public Json set(int index, Object value) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     * Combine this object or array with the passed in object or array. The types of
     * <code>this</code> and the <code>object</code> argument must match. If both are
     * <code>Json</code> objects, all properties of the parameter are added to <code>this</code>.
     * If both are arrays, all elements of the parameter are appended to <code>this</code>
     * </p>
     *
     * @param object  The object or array whose properties or elements must be added to this
     *                Json object or array.
     * @param options A sequence of options that governs the merging process.
     * @return this
     */
    public Json with(Json object, Json[] options) {
        throw new UnsupportedOperationException();
    }

    /**
     * Same as <code>{}@link #with(Json,Json...options)}</code> with each option
     * argument converted to <code>Json</code> first.
     */
    public Json with(Json object, Object... options) {
        Json[] jopts = new Json[options.length];
        for (int i = 0; i < jopts.length; i++)
            jopts[i] = make(options[i]);
        return with(object, jopts);
    }

    /**
     * @return the underlying value of this <code>Json</code> entity. The actual value will
     * be a Java Boolean, String, Number, Map, List or null. For complex entities (objects
     * or arrays), the method will perform a deep copy and extra underlying values recursively
     * for all nested elements.
     */
    public Object getValue() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the boolean value of a boolean <code>Json</code> instance. Call
     * {@link #isBoolean()} first if you're not sure this instance is indeed a
     * boolean.
     */
    public boolean asBoolean() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the string value of a string <code>Json</code> instance. Call
     * {@link #isString()} first if you're not sure this instance is indeed a
     * string.
     */
    public String asString() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the integer value of a number <code>Json</code> instance. Call
     * {@link #isNumber()} first if you're not sure this instance is indeed a
     * number.
     */
    public int asInteger() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the float value of a float <code>Json</code> instance. Call
     * {@link #isNumber()} first if you're not sure this instance is indeed a
     * number.
     */
    public float asFloat() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the double value of a number <code>Json</code> instance. Call
     * {@link #isNumber()} first if you're not sure this instance is indeed a
     * number.
     */
    public double asDouble() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the long value of a number <code>Json</code> instance. Call
     * {@link #isNumber()} first if you're not sure this instance is indeed a
     * number.
     */
    public long asLong() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the short value of a number <code>Json</code> instance. Call
     * {@link #isNumber()} first if you're not sure this instance is indeed a
     * number.
     */
    public short asShort() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the byte value of a number <code>Json</code> instance. Call
     * {@link #isNumber()} first if you're not sure this instance is indeed a
     * number.
     */
    public byte asByte() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the first character of a string <code>Json</code> instance. Call
     * {@link #isString()} first if you're not sure this instance is indeed a
     * string.
     */
    public char asChar() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return a map of the properties of an object <code>Json</code> instance. The map
     * is a clone of the object and can be modified safely without affecting it. Call
     * {@link #isObject()} first if you're not sure this instance is indeed a
     * <code>Json</code> object.
     */
    public Map<String, Object> asMap() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the underlying map of properties of a <code>Json</code> object. The returned
     * map is the actual object representation so any modifications to it are modifications
     * of the <code>Json</code> object itself. Call
     * {@link #isObject()} first if you're not sure this instance is indeed a
     * <code>Json</code> object.
     */
    public Map<String, Json> asJsonMap() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return a list of the elements of a <code>Json</code> array. The list is a clone
     * of the array and can be modified safely without affecting it. Call
     * {@link #isArray()} first if you're not sure this instance is indeed a
     * <code>Json</code> array.
     */
    public List<Object> asList() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the underlying {@link List} representation of a <code>Json</code> array.
     * The returned list is the actual array representation so any modifications to it
     * are modifications of the <code>Json</code> array itself. Call
     * {@link #isArray()} first if you're not sure this instance is indeed a
     * <code>Json</code> array.
     */
    public List<Json> asJsonList() {
        throw new UnsupportedOperationException();
    }

    // Mohist start
    public boolean asBoolean(String property) {
        throw new UnsupportedOperationException();
    }

    public String asString(String property) {
        throw new UnsupportedOperationException();
    }

    public int asInteger(String property) {
        throw new UnsupportedOperationException();
    }

    public float asFloat(String property) {
        throw new UnsupportedOperationException();
    }

    public double asDouble(String property) {
        throw new UnsupportedOperationException();
    }

    public long asLong(String property) {
        throw new UnsupportedOperationException();
    }

    public short asShort(String property) {
        throw new UnsupportedOperationException();
    }

    public byte asByte(String property) {
        throw new UnsupportedOperationException();
    }

    public char asChar(String property) {
        throw new UnsupportedOperationException();
    }

    public Map<String, Object> asMap(String property) {
        throw new UnsupportedOperationException();
    }

    public Map<String, Json> asJsonMap(String property) {
        throw new UnsupportedOperationException();
    }

    public List<Object> asList(String property) {
        throw new UnsupportedOperationException();
    }

    public List<Json> asJsonList(String property) {
        throw new UnsupportedOperationException();
    }

    public <T> T asBean(Class<T> classZ) {
        throw new UnsupportedOperationException();
    }

    public Properties asProperties() {
        throw new UnsupportedOperationException();
    }

    public byte[] asBytes() {
        throw new UnsupportedOperationException();
    }
    // Mohist end

    /**
     * @return <code>true</code> if this is a <code>Json</code> null entity
     * and <code>false</code> otherwise.
     */
    public boolean isNull() {
        return false;
    }

    /**
     * @return <code>true</code> if this is a <code>Json</code> string entity
     * and <code>false</code> otherwise.
     */
    public boolean isString() {
        return false;
    }

    /**
     * @return <code>true</code> if this is a <code>Json</code> number entity
     * and <code>false</code> otherwise.
     */
    public boolean isNumber() {
        return false;
    }

    /**
     * @return <code>true</code> if this is a <code>Json</code> boolean entity
     * and <code>false</code> otherwise.
     */
    public boolean isBoolean() {
        return false;
    }

    /**
     * @return <code>true</code> if this is a <code>Json</code> array (i.e. list) entity
     * and <code>false</code> otherwise.
     */
    public boolean isArray() {
        return false;
    }

    /**
     * @return <code>true</code> if this is a <code>Json</code> object entity
     * and <code>false</code> otherwise.
     */
    public boolean isObject() {
        return false;
    }

    /**
     * @return <code>true</code> if this is a <code>Json</code> primitive entity
     * (one of string, number or boolean) and <code>false</code> otherwise.
     */
    public boolean isPrimitive() {
        return isString() || isNumber() || isBoolean();
    }

    /**
     * <p>
     * Json-pad this object as an argument to a callback function.
     * </p>
     *
     * @param callback The name of the callback function. Can be null or empty,
     *                 in which case no padding is done.
     * @return The jsonpadded, stringified version of this object if the <code>callback</code>
     * is not null or empty, or just the stringified version of the object.
     */
    public String pad(String callback) {
        return (callback != null && !callback.isEmpty())
                ? callback + "(" + this + ");"
                : toString();
    }

    /**
     * Return an object representing the complete configuration
     * of a merge. The properties of the object represent paths
     * of the JSON structure being merged and the values represent
     * the set of options that apply to each path.
     *
     * @param options the configuration options
     * @return the configuration object
     */
    protected Json collectWithOptions(Json... options) {
        Json result = object();
        for (Json opt : options) {
            if (opt.isString()) {
                if (!result.has(""))
                    result.set("", object());
                result.at("").set(opt.asString(), true);
            } else {
                if (!opt.has("for"))
                    opt.set("for", array(""));
                Json forPaths = opt.at("for");
                if (!forPaths.isArray())
                    forPaths = array(forPaths);
                for (Json path : forPaths.asJsonList()) {
                    if (!result.has(path.asString()))
                        result.set(path.asString(), object());
                    Json at_path = result.at(path.asString());
                    at_path.set("merge", opt.is("merge", true));
                    at_path.set("dup", opt.is("dup", true));
                    at_path.set("sort", opt.is("sort", true));
                    at_path.set("compareBy", opt.at("compareBy", nil()));
                }
            }
        }
        return result;
    }

    /**
     * <p>
     * This interface defines how <code>Json</code> instances are constructed. There is a
     * default implementation for each kind of <code>Json</code> value, but you can provide
     * your own implementation. For example, you might want a different representation of
     * an object than a regular <code>HashMap</code>. Or you might want string comparison to be
     * case insensitive.
     * </p>
     *
     * <p>
     * In addition, the {@link #make(Object)} method allows you plug-in your own mapping
     * of arbitrary Java objects to <code>Json</code> instances. You might want to implement
     * a Java Beans to JSON mapping or any other JSON serialization that makes sense in your
     * project.
     * </p>
     *
     * <p>
     * To avoid implementing all methods in that interface, you can extend the {@link DefaultFactory}
     * default implementation and simply overwrite the ones you're interested in.
     * </p>
     *
     * <p>
     * The factory implementation used by the <code>Json</code> classes is specified simply by calling
     * the {@link #setGlobalFactory(Factory)} method. The factory is a static, global variable by default.
     * If you need different factories in different areas of a single application, you may attach them
     * to different threads of execution using the {@link #attachFactory(Factory)}. Recall a separate
     * copy of static variables is made per ClassLoader, so for example in a web application context, that
     * global factory can be different for each web application (as Java web servers usually use a separate
     * class loader per application). Thread-local factories are really a provision for special cases.
     * </p>
     *
     * @author Borislav Iordanov
     */
    public interface Factory {
        /**
         * Construct and return an object representing JSON <code>null</code>. Implementations are
         * free to cache a return the same instance. The resulting value must return
         * <code>true</code> from <code>isNull()</code> and <code>null</code> from
         * <code>getValue()</code>.
         *
         * @return The representation of a JSON <code>null</code> value.
         */
        Json nil();

        /**
         * Construct and return a JSON boolean. The resulting value must return
         * <code>true</code> from <code>isBoolean()</code> and the passed
         * in parameter from <code>getValue()</code>.
         *
         * @param value The boolean value.
         * @return A JSON with <code>isBoolean() == true</code>. Implementations
         * are free to cache and return the same instance for true and false.
         */
        Json bool(boolean value);

        /**
         * Construct and return a JSON string. The resulting value must return
         * <code>true</code> from <code>isString()</code> and the passed
         * in parameter from <code>getValue()</code>.
         *
         * @param value The string to wrap as a JSON value.
         * @return A JSON element with the given string as a value.
         */
        Json string(String value);

        /**
         * Construct and return a JSON number. The resulting value must return
         * <code>true</code> from <code>isNumber()</code> and the passed
         * in parameter from <code>getValue()</code>.
         *
         * @param value The numeric value.
         * @return Json instance representing that value.
         */
        Json number(Number value);

        /**
         * Construct and return a JSON object. The resulting value must return
         * <code>true</code> from <code>isObject()</code> and an implementation
         * of <code>java.util.Map</code> from <code>getValue()</code>.
         *
         * @return An empty JSON object.
         */
        Json object();

        /**
         * Construct and return a JSON object. The resulting value must return
         * <code>true</code> from <code>isArray()</code> and an implementation
         * of <code>java.util.List</code> from <code>getValue()</code>.
         *
         * @return An empty JSON array.
         */
        Json array();

        /**
         * Construct and return a JSON object. The resulting value can be of any
         * JSON type. The method is responsible for examining the type of its
         * argument and performing an appropriate mapping to a <code>Json</code>
         * instance.
         *
         * @param anything An arbitray Java object from which to construct a <code>Json</code>
         *                 element.
         * @return The newly constructed <code>Json</code> instance.
         */
        Json make(Object anything);
    }

    public interface Function<T, R> {

        /**
         * Applies this function to the given argument.
         *
         * @param t the function argument
         * @return the function result
         */
        R apply(T t);
    }

    //-------------------------------------------------------------------------
    // END OF PUBLIC INTERFACE
    //-------------------------------------------------------------------------

    /**
     * <p>
     * Represents JSON schema - a specific data format that a JSON entity must
     * follow. The idea of a JSON schema is very similar to XML. Its main purpose
     * is validating input.
     * </p>
     *
     * <p>
     * More information about the various JSON schema specifications can be
     * found at http://json-schema.org. JSON Schema is an  IETF draft (v4 currently) and
     * our implementation follows this set of specifications. A JSON schema is specified
     * as a JSON object that contains keywords defined by the specification. Here are
     * a few introductory materials:
     * <ul>
     * <li>http://jsonary.com/documentation/json-schema/ -
     * a very well-written tutorial covering the whole standard</li>
     * <li>http://spacetelescope.github.io/understanding-json-schema/ -
     * online book, tutorial (Python/Ruby based)</li>
     * </ul>
     *
     * @author Borislav Iordanov
     */
    public interface Schema {
        /**
         * <p>
         * Validate a JSON document according to this schema. The validations attempts to
         * proceed even in the face of errors. The return value is always a <code>Json.object</code>
         * containing the boolean property <code>ok</code>. When <code>ok</code> is <code>true</code>,
         * the return object contains nothing else. When it is <code>false</code>, the return object
         * contains a property <code>errors</code> which is an array of error messages for all
         * detected schema violations.
         * </p>
         *
         * @param document The input document.
         * @return <code>{"ok":true}</code> or <code>{"ok":false, errors:["msg1", "msg2", ...]}</code>
         */
        Json validate(Json document);

        /**
         * <p>Return the JSON representation of the schema.</p>
         */
        Json toJson();

        /**
         * <p>Possible options are: <code>ignoreDefaults:true|false</code>.
         * </p>
         * @return A newly created <code>Json</code> conforming to this schema.
         */
        //Json generate(Json options);
    }

    static class DefaultSchema implements Schema {
        // Anything is valid schema
        static Instruction any = param -> null;
        int maxchars = 50;
        URI uri;
        Json theschema;
        Instruction start;

        DefaultSchema(URI uri, Json theschema, Function<URI, Json> relativeReferenceResolver) {
            try {
                this.uri = uri == null ? new URI("") : uri;
                if (relativeReferenceResolver == null)
                    relativeReferenceResolver = docuri -> {
                        try {
                            return Json.read(fetchContent(docuri.toURL()));
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    };
                this.theschema = theschema.dup();
                this.theschema = expandReferences(this.theschema,
                        this.theschema,
                        this.uri,
                        new LinkedHashMap<>(),
                        new IdentityHashMap<>(),
                        relativeReferenceResolver);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            this.start = compile(this.theschema, new IdentityHashMap<>());
        }

        static Json maybeError(Json errors, Json E) {
            return E == null ? errors : (errors == null ? Json.array() : errors).with(E, new Json[0]);
        }

        Instruction compile(Json S, Map<Json, Instruction> compiled) {
            Instruction result = compiled.get(S);
            if (result != null)
                return result;
            Sequence seq = new Sequence();
            compiled.put(S, seq);
            if (S.has("type") && !S.is("type", "any"))
                seq.add(new CheckType(S.at("type").isString() ?
                        Json.array().add(S.at("type")) : S.at("type")));
            if (S.has("enum"))
                seq.add(new CheckEnum(S.at("enum")));
            if (S.has("allOf")) {
                Sequence sub = new Sequence();
                for (Json x : S.at("allOf").asJsonList())
                    sub.add(compile(x, compiled));
                seq.add(sub);
            }
            if (S.has("anyOf")) {
                CheckAny any = new CheckAny();
                any.schema = S.at("anyOf");
                for (Json x : any.schema.asJsonList())
                    any.alternates.add(compile(x, compiled));
                seq.add(any);
            }
            if (S.has("oneOf")) {
                CheckOne any = new CheckOne();
                any.schema = S.at("oneOf");
                for (Json x : any.schema.asJsonList())
                    any.alternates.add(compile(x, compiled));
                seq.add(any);
            }
            if (S.has("not"))
                seq.add(new CheckNot(compile(S.at("not"), compiled), S.at("not")));

            if (S.has("required") && S.at("required").isArray()) {
                for (Json p : S.at("required").asJsonList())
                    seq.add(new CheckPropertyPresent(p.asString()));
            }
            CheckObject objectCheck = new CheckObject();
            if (S.has("properties"))
                for (Map.Entry<String, Json> p : S.at("properties").asJsonMap().entrySet())
                    objectCheck.props.add(new CheckObject.CheckProperty(
                            p.getKey(), compile(p.getValue(), compiled)));
            if (S.has("patternProperties"))
                for (Map.Entry<String, Json> p : S.at("patternProperties").asJsonMap().entrySet())
                    objectCheck.patternProps.add(new CheckObject.CheckPatternProperty(p.getKey(),
                            compile(p.getValue(), compiled)));
            if (S.has("additionalProperties")) {
                if (S.at("additionalProperties").isObject())
                    objectCheck.additionalSchema = compile(S.at("additionalProperties"), compiled);
                else if (!S.at("additionalProperties").asBoolean())
                    objectCheck.additionalSchema = null; // means no additional properties allowed
            }
            if (S.has("minProperties"))
                objectCheck.min = S.at("minProperties").asInteger();
            if (S.has("maxProperties"))
                objectCheck.max = S.at("maxProperties").asInteger();

            if (!objectCheck.props.isEmpty() || !objectCheck.patternProps.isEmpty() ||
                    objectCheck.additionalSchema != any ||
                    objectCheck.min > 0 || objectCheck.max < Integer.MAX_VALUE)
                seq.add(objectCheck);

            CheckArray arrayCheck = new CheckArray();
            if (S.has("items"))
                if (S.at("items").isObject())
                    arrayCheck.schema = compile(S.at("items"), compiled);
                else {
                    arrayCheck.schemas = new ArrayList<>();
                    for (Json s : S.at("items").asJsonList())
                        arrayCheck.schemas.add(compile(s, compiled));
                }
            if (S.has("additionalItems"))
                if (S.at("additionalItems").isObject())
                    arrayCheck.additionalSchema = compile(S.at("additionalItems"), compiled);
                else if (!S.at("additionalItems").asBoolean())
                    arrayCheck.additionalSchema = null;
            if (S.has("uniqueItems"))
                arrayCheck.uniqueitems = S.at("uniqueItems").asBoolean();
            if (S.has("minItems"))
                arrayCheck.min = S.at("minItems").asInteger();
            if (S.has("maxItems"))
                arrayCheck.max = S.at("maxItems").asInteger();
            if (arrayCheck.schema != null || arrayCheck.schemas != null ||
                    arrayCheck.additionalSchema != any ||
                    arrayCheck.uniqueitems != null ||
                    arrayCheck.max < Integer.MAX_VALUE || arrayCheck.min > 0)
                seq.add(arrayCheck);

            CheckNumber numberCheck = new CheckNumber();
            if (S.has("minimum"))
                numberCheck.min = S.at("minimum").asDouble();
            if (S.has("maximum"))
                numberCheck.max = S.at("maximum").asDouble();
            if (S.has("multipleOf"))
                numberCheck.multipleOf = S.at("multipleOf").asDouble();
            if (S.has("exclusiveMinimum"))
                numberCheck.exclusiveMin = S.at("exclusiveMinimum").asBoolean();
            if (S.has("exclusiveMaximum"))
                numberCheck.exclusiveMax = S.at("exclusiveMaximum").asBoolean();
            if (!Double.isNaN(numberCheck.min) || !Double.isNaN(numberCheck.max) || !Double.isNaN(numberCheck.multipleOf))
                seq.add(numberCheck);

            CheckString stringCheck = new CheckString();
            if (S.has("minLength"))
                stringCheck.min = S.at("minLength").asInteger();
            if (S.has("maxLength"))
                stringCheck.max = S.at("maxLength").asInteger();
            if (S.has("pattern"))
                stringCheck.pattern = Pattern.compile(S.at("pattern").asString());
            if (stringCheck.min > 0 || stringCheck.max < Integer.MAX_VALUE || stringCheck.pattern != null)
                seq.add(stringCheck);

            if (S.has("dependencies"))
                for (Map.Entry<String, Json> e : S.at("dependencies").asJsonMap().entrySet())
                    if (e.getValue().isObject())
                        seq.add(new CheckSchemaDependency(e.getKey(), compile(e.getValue(), compiled)));
                    else if (e.getValue().isArray())
                        seq.add(new CheckPropertyDependency(e.getKey(), e.getValue()));
                    else
                        seq.add(new CheckPropertyDependency(e.getKey(), Json.array(e.getValue())));
            result = seq.seq.size() == 1 ? seq.seq.get(0) : seq;
            compiled.put(S, result);
            return result;
        }

        public Json validate(Json document) {
            Json result = Json.object("ok", true);
            Json errors = start.apply(document);
            return errors == null ? result : result.set("errors", errors).set("ok", false);
        }

        public Json toJson() {
            return theschema;
        }

        public Json generate(Json options) {
            // TODO...
            return Json.nil();
        }

        interface Instruction extends Function<Json, Json> {
        }

        static class CheckNumber implements Instruction {
            double min = Double.NaN, max = Double.NaN, multipleOf = Double.NaN;
            boolean exclusiveMin = false, exclusiveMax = false;

            public Json apply(Json param) {
                Json errors = null;
                if (!param.isNumber()) return null;
                double value = param.asDouble();
                if (!Double.isNaN(min) && (value < min || exclusiveMin && value == min))
                    errors = maybeError(null, Json.make("Number " + param + " is below allowed minimum " + min));
                if (!Double.isNaN(max) && (value > max || exclusiveMax && value == max))
                    errors = maybeError(errors, Json.make("Number " + param + " is above allowed maximum " + max));
                if (!Double.isNaN(multipleOf) && (value / multipleOf) % 1 != 0)
                    errors = maybeError(errors, Json.make("Number " + param + " is not a multiple of  " + multipleOf));
                return errors;
            }
        }

        static class Sequence implements Instruction {
            ArrayList<Instruction> seq = new ArrayList<>();

            public Json apply(Json param) {
                Json errors = null;
                for (Instruction I : seq)
                    errors = maybeError(errors, I.apply(param));
                return errors;
            }

            public Sequence add(Instruction I) {
                seq.add(I);
                return this;
            }
        }

        static class CheckSchemaDependency implements Instruction {
            Instruction schema;
            String property;

            public CheckSchemaDependency(String property, Instruction schema) {
                this.property = property;
                this.schema = schema;
            }

            public Json apply(Json param) {
                if (!param.isObject()) return null;
                else if (!param.has(property)) return null;
                else return (schema.apply(param));
            }
        }

        // Type validation
        class IsObject implements Instruction {
            public Json apply(Json param) {
                return param.isObject() ? null : Json.make(param.toString(maxchars));
            }
        }

        class IsArray implements Instruction {
            public Json apply(Json param) {
                return param.isArray() ? null : Json.make(param.toString(maxchars));
            }
        }

        class IsString implements Instruction {
            public Json apply(Json param) {
                return param.isString() ? null : Json.make(param.toString(maxchars));
            }
        }

        class IsBoolean implements Instruction {
            public Json apply(Json param) {
                return param.isBoolean() ? null : Json.make(param.toString(maxchars));
            }
        }

        class IsNull implements Instruction {
            public Json apply(Json param) {
                return param.isNull() ? null : Json.make(param.toString(maxchars));
            }
        }

        class IsNumber implements Instruction {
            public Json apply(Json param) {
                return param.isNumber() ? null : Json.make(param.toString(maxchars));
            }
        }

        class IsInteger implements Instruction {
            public Json apply(Json param) {
                return param.isNumber() && param.getValue() instanceof Integer ? null : Json.make(param.toString(maxchars));
            }
        }

        class CheckString implements Instruction {
            int min = 0, max = Integer.MAX_VALUE;
            Pattern pattern;

            public Json apply(Json param) {
                Json errors = null;
                if (!param.isString()) return null;
                String s = param.asString();
                final int size = s.codePointCount(0, s.length());
                if (size < min || size > max)
                    errors = maybeError(null, Json.make("String  " + param.toString(maxchars) +
                            " has length outside of the permitted range [" + min + "," + max + "]."));
                if (pattern != null && !pattern.matcher(s).matches())
                    errors = maybeError(errors, Json.make("String  " + param.toString(maxchars) +
                            " does not match regex " + pattern.toString()));
                return errors;
            }
        }

        class CheckArray implements Instruction {
            int min = 0, max = Integer.MAX_VALUE;
            Boolean uniqueitems = null;
            Instruction additionalSchema = any;
            Instruction schema;
            ArrayList<Instruction> schemas;

            public Json apply(Json param) {
                Json errors = null;
                if (!param.isArray()) return null;
                if (schema == null && schemas == null && additionalSchema == null) // no schema specified
                    return null;
                int size = param.asJsonList().size();
                for (int i = 0; i < size; i++) {
                    Instruction S = schema != null ? schema
                            : (schemas != null && i < schemas.size()) ? schemas.get(i) : additionalSchema;
                    if (S == null)
                        errors = maybeError(errors, Json.make("Additional items are not permitted: " +
                                param.at(i) + " in " + param.toString(maxchars)));
                    else
                        errors = maybeError(errors, S.apply(param.at(i)));
                    if (uniqueitems != null && uniqueitems && param.asJsonList().lastIndexOf(param.at(i)) > i)
                        errors = maybeError(errors, Json.make("Element " + param.at(i) + " is duplicate in array."));
                    if (errors != null && !errors.asJsonList().isEmpty())
                        break;
                }
                if (size < min || size > max)
                    errors = maybeError(errors, Json.make("Array  " + param.toString(maxchars) +
                            " has number of elements outside of the permitted range [" + min + "," + max + "]."));
                return errors;
            }
        }

        class CheckPropertyPresent implements Instruction {
            String propname;

            public CheckPropertyPresent(String propname) {
                this.propname = propname;
            }

            public Json apply(Json param) {
                if (!param.isObject()) return null;
                if (param.has(propname)) return null;
                else return Json.array().add(Json.make("Required property " + propname +
                        " missing from object " + param.toString(maxchars)));
            }
        }

        class CheckObject implements Instruction {
            int min = 0, max = Integer.MAX_VALUE;
            Instruction additionalSchema = any;
            ArrayList<CheckProperty> props = new ArrayList<>();
            ArrayList<CheckPatternProperty> patternProps = new ArrayList<>();

            public Json apply(Json param) {
                Json errors = null;
                if (!param.isObject()) return null;
                HashSet<String> checked = new HashSet<>();
                for (CheckProperty I : props) {
                    if (param.has(I.name)) checked.add(I.name);
                    errors = maybeError(errors, I.apply(param));
                }
                for (CheckPatternProperty I : patternProps) {

                    errors = maybeError(errors, I.apply(param, checked));
                }
                if (additionalSchema != any) for (Map.Entry<String, Json> e : param.asJsonMap().entrySet())
                    if (!checked.contains(e.getKey()))
                        errors = maybeError(errors, additionalSchema == null ?
                                Json.make("Extra property '" + e.getKey() +
                                        "', schema doesn't allow any properties not explicitly defined:" +
                                        param.toString(maxchars))
                                : additionalSchema.apply(e.getValue()));
                if (param.asJsonMap().size() < min)
                    errors = maybeError(errors, Json.make("Object " + param.toString(maxchars) +
                            " has fewer than the permitted " + min + "  number of properties."));
                if (param.asJsonMap().size() > max)
                    errors = maybeError(errors, Json.make("Object " + param.toString(maxchars) +
                            " has more than the permitted " + min + "  number of properties."));
                return errors;
            }

            // Object validation
            static class CheckProperty implements Instruction {
                String name;
                Instruction schema;

                public CheckProperty(String name, Instruction schema) {
                    this.name = name;
                    this.schema = schema;
                }

                public Json apply(Json param) {
                    Json value = param.at(name);
                    if (value == null)
                        return null;
                    else
                        return schema.apply(param.at(name));
                }
            }

            static class CheckPatternProperty // implements Instruction
            {
                Pattern pattern;
                Instruction schema;

                public CheckPatternProperty(String pattern, Instruction schema) {
                    this.pattern = Pattern.compile(pattern);
                    this.schema = schema;
                }

                public Json apply(Json param, Set<String> found) {
                    Json errors = null;
                    for (Map.Entry<String, Json> e : param.asJsonMap().entrySet())
                        if (pattern.matcher(e.getKey()).find()) {
                            found.add(e.getKey());
                            errors = maybeError(errors, schema.apply(e.getValue()));
                        }
                    return errors;
                }
            }
        }

        class CheckType implements Instruction {
            Json types;

            public CheckType(Json types) {
                this.types = types;
            }

            public Json apply(Json param) {
                String ptype = param.isString() ? "string" :
                        param.isObject() ? "object" :
                                param.isArray() ? "array" :
                                        param.isNumber() ? "number" :
                                                param.isNull() ? "null" : "boolean";
                for (Json type : types.asJsonList())
                    if (type.asString().equals(ptype))
                        return null;
                    else if (type.asString().equals("integer") &&
                            param.isNumber() &&
                            param.asDouble() % 1 == 0)
                        return null;
                return Json.array().add(Json.make("Type mistmatch for " + param.toString(maxchars) +
                        ", allowed types: " + types));
            }
        }

        class CheckEnum implements Instruction {
            Json theenum;

            public CheckEnum(Json theenum) {
                this.theenum = theenum;
            }

            public Json apply(Json param) {
                for (Json option : theenum.asJsonList())
                    if (param.equals(option))
                        return null;
                return Json.array().add("Element " + param.toString(maxchars) +
                        " doesn't match any of enumerated possibilities " + theenum);
            }
        }

        class CheckAny implements Instruction {
            ArrayList<Instruction> alternates = new ArrayList<>();
            Json schema;

            public Json apply(Json param) {
                for (Instruction I : alternates)
                    if (I.apply(param) == null)
                        return null;
                return Json.array().add("Element " + param.toString(maxchars) +
                        " must conform to at least one of available sub-schemas " +
                        schema.toString(maxchars));
            }
        }

        class CheckOne implements Instruction {
            ArrayList<Instruction> alternates = new ArrayList<>();
            Json schema;

            public Json apply(Json param) {
                int matches = 0;
                Json errors = Json.array();
                for (Instruction I : alternates) {
                    Json result = I.apply(param);
                    if (result == null)
                        matches++;
                    else
                        errors.add(result);
                }
                if (matches != 1) {
                    return Json.array().add("Element " + param.toString(maxchars) +
                            " must conform to exactly one of available sub-schemas, but not more " +
                            schema.toString(maxchars)).add(errors);
                } else
                    return null;
            }
        }

        class CheckNot implements Instruction {
            Instruction I;
            Json schema;

            public CheckNot(Instruction I, Json schema) {
                this.I = I;
                this.schema = schema;
            }

            public Json apply(Json param) {
                if (I.apply(param) != null)
                    return null;
                else
                    return Json.array().add("Element " + param.toString(maxchars) +
                            " must NOT conform to the schema " + schema.toString(maxchars));
            }
        }

        class CheckPropertyDependency implements Instruction {
            Json required;
            String property;

            public CheckPropertyDependency(String property, Json required) {
                this.property = property;
                this.required = required;
            }

            public Json apply(Json param) {
                if (!param.isObject()) return null;
                if (!param.has(property)) return null;
                else {
                    Json errors = null;
                    for (Json p : required.asJsonList())
                        if (!param.has(p.asString()))
                            errors = maybeError(errors, Json.make("Conditionally required property " + p +
                                    " missing from object " + param.toString(maxchars)));
                    return errors;
                }
            }
        }
    }

    public static class DefaultFactory implements Factory {
        public Json nil() {
            return new NullJson();
        }

        public Json bool(boolean x) {
            return new BooleanJson(x ? Boolean.TRUE : Boolean.FALSE, null);
        }

        public Json string(String x) {
            return new StringJson(x, null);
        }

        public Json number(Number x) {
            return new NumberJson(x, null);
        }

        public Json array() {
            return new ArrayJson();
        }

        public Json object() {
            return new ObjectJson();
        }

        public Json make(Object anything) {
            if (anything == null)
                return nil();
            else if (anything instanceof Json)
                return (Json) anything;
            else if (anything instanceof String)
                return factory().string((String) anything);
            else if (anything instanceof Collection<?>) {
                Json L = array();
                for (Object x : (Collection<?>) anything)
                    L.add(factory().make(x));
                return L;
            } else if (anything instanceof Map<?, ?>) {
                Json O = object();
                for (Map.Entry<?, ?> x : ((Map<?, ?>) anything).entrySet())
                    O.set(x.getKey().toString(), factory().make(x.getValue()));
                return O;
            } else if (anything instanceof Boolean)
                return factory().bool((Boolean) anything);
            else if (anything instanceof Number)
                return factory().number((Number) anything);
            else if (anything.getClass().isArray()) {
                Class<?> comp = anything.getClass().getComponentType();
                if (!comp.isPrimitive())
                    return Json.array((Object[]) anything);
                Json A = array();
                if (boolean.class == comp)
                    for (boolean b : (boolean[]) anything) A.add(b);
                else if (byte.class == comp)
                    for (byte b : (byte[]) anything) A.add(b);
                else if (char.class == comp)
                    for (char b : (char[]) anything) A.add(b);
                else if (short.class == comp)
                    for (short b : (short[]) anything) A.add(b);
                else if (int.class == comp)
                    for (int b : (int[]) anything) A.add(b);
                else if (long.class == comp)
                    for (long b : (long[]) anything) A.add(b);
                else if (float.class == comp)
                    for (float b : (float[]) anything) A.add(b);
                else if (double.class == comp)
                    for (double b : (double[]) anything) A.add(b);
                return A;
            } else
                throw new IllegalArgumentException("Don't know how to convert to Json : " + anything);
        }
    }

    final static class Escaper {

        private static final char[] HEX_CHARS = {
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        };

        private static final Set<Character> JS_ESCAPE_CHARS;
        private static final Set<Character> HTML_ESCAPE_CHARS;

        static {
            JS_ESCAPE_CHARS = Set.of('"', '\\');
            HTML_ESCAPE_CHARS = Set.of('<', '>', '&', '=', '\'');
        }

        private final boolean escapeHtmlCharacters;

        Escaper(boolean escapeHtmlCharacters) {
            this.escapeHtmlCharacters = escapeHtmlCharacters;
        }

        private static boolean isControlCharacter(int codePoint) {
            // JSON spec defines these code points as control characters, so they must be escaped
            return codePoint < 0x20
                    || codePoint == 0x2028  // Line separator
                    || codePoint == 0x2029  // Paragraph separator
                    || (codePoint >= 0x7f && codePoint <= 0x9f);
        }

        private static void appendHexJavaScriptRepresentation(int codePoint, Appendable out)
                throws IOException {
            if (Character.isSupplementaryCodePoint(codePoint)) {
                // Handle supplementary unicode values which are not representable in
                // javascript.  We deal with these by escaping them as two 4B sequences
                // so that they will round-trip properly when sent from java to javascript
                // and back.
                char[] surrogates = Character.toChars(codePoint);
                appendHexJavaScriptRepresentation(surrogates[0], out);
                appendHexJavaScriptRepresentation(surrogates[1], out);
                return;
            }
            out.append("\\u")
                    .append(HEX_CHARS[(codePoint >>> 12) & 0xf])
                    .append(HEX_CHARS[(codePoint >>> 8) & 0xf])
                    .append(HEX_CHARS[(codePoint >>> 4) & 0xf])
                    .append(HEX_CHARS[codePoint & 0xf]);
        }

        public String escapeJsonString(CharSequence plainText) {
            StringBuilder escapedString = new StringBuilder(plainText.length() + 20);
            try {
                escapeJsonString(plainText, escapedString);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return escapedString.toString();
        }

        private void escapeJsonString(CharSequence plainText, StringBuilder out) throws IOException {
            int pos = 0;  // Index just past the last char in plainText written to out.
            int len = plainText.length();

            for (int charCount, i = 0; i < len; i += charCount) {
                int codePoint = Character.codePointAt(plainText, i);
                charCount = Character.charCount(codePoint);

                if (!isControlCharacter(codePoint) && !mustEscapeCharInJsString(codePoint)) {
                    continue;
                }

                out.append(plainText, pos, i);
                pos = i + charCount;
                switch (codePoint) {
                    case '\b' -> out.append("\\b");
                    case '\t' -> out.append("\\t");
                    case '\n' -> out.append("\\n");
                    case '\f' -> out.append("\\f");
                    case '\r' -> out.append("\\r");
                    case '\\' -> out.append("\\\\");
                    case '/' -> out.append("\\/");
                    case '"' -> out.append("\\\"");
                    default -> appendHexJavaScriptRepresentation(codePoint, out);
                }
            }
            out.append(plainText, pos, len);
        }

        private boolean mustEscapeCharInJsString(int codepoint) {
            if (!Character.isSupplementaryCodePoint(codepoint)) {
                char c = (char) codepoint;
                return JS_ESCAPE_CHARS.contains(c)
                        || (escapeHtmlCharacters && HTML_ESCAPE_CHARS.contains(c));
            }
            return false;
        }
    }

    public static class MalformedJsonException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public MalformedJsonException(String msg) {
            super(msg);
        }
    }

    private static class Reader {
        public static final int FIRST = 0;
        public static final int CURRENT = 1;
        public static final int NEXT = 2;
        private static final Object OBJECT_END = "}";
        private static final Object ARRAY_END = "]";
        private static final Object OBJECT_START = "{";
        private static final Object ARRAY_START = "[";
        private static final Object COLON = ":";
        private static final Object COMMA = ",";
        private static final HashSet<Object> PUNCTUATION = new HashSet<>(
                Arrays.asList(OBJECT_END, OBJECT_START, ARRAY_END, ARRAY_START, COLON, COMMA));
        private static final Map<Character, Character> escapes = new HashMap<>();

        static {
            escapes.put('"', '"');
            escapes.put('\\', '\\');
            escapes.put('/', '/');
            escapes.put('b', '\b');
            escapes.put('f', '\f');
            escapes.put('n', '\n');
            escapes.put('r', '\r');
            escapes.put('t', '\t');
        }

        private final StringBuffer buf = new StringBuffer();
        private CharacterIterator it;
        private char c;
        private Object token;

        private char next() {
            if (it.getIndex() == it.getEndIndex())
                throw new MalformedJsonException("Reached end of input at the " +
                        it.getIndex() + "th character.");
            c = it.next();
            return c;
        }

        private char previous() {
            c = it.previous();
            return c;
        }

        private void skipWhiteSpace() {
            do {
                if (Character.isWhitespace(c))
                    ;
                else if (c == '/') {
                    next();
                    if (c == '*') {
                        // skip multiline comments
                        while (c != CharacterIterator.DONE)
                            if (next() == '*' && next() == '/')
                                break;
                        if (c == CharacterIterator.DONE)
                            throw new MalformedJsonException("Unterminated comment while parsing JSON string.");
                    } else if (c == '/')
                        while (c != '\n' && c != CharacterIterator.DONE)
                            next();
                    else {
                        previous();
                        break;
                    }
                } else
                    break;
            } while (next() != CharacterIterator.DONE);
        }

        public Object read(CharacterIterator ci, int start) {
            it = ci;
            switch (start) {
                case FIRST -> c = it.first();
                case CURRENT -> c = it.current();
                case NEXT -> c = it.next();
            }
            return read();
        }

        public Object read(CharacterIterator it) {
            return read(it, NEXT);
        }

        public Object read(String string) {
            return read(new StringCharacterIterator(string), FIRST);
        }

        private void expected(Object expectedToken, Object actual) {
            if (expectedToken != actual)
                throw new MalformedJsonException("Expected " + expectedToken + ", but got " + actual + " instead");
        }

        @SuppressWarnings("unchecked")
        private <T> T read() {
            skipWhiteSpace();
            char ch = c;
            next();
            switch (ch) {
                case '"' -> token = readString();
                case '[' -> token = readArray();
                case ']' -> token = ARRAY_END;
                case ',' -> token = COMMA;
                case '{' -> token = readObject();
                case '}' -> token = OBJECT_END;
                case ':' -> token = COLON;
                case 't' -> {
                    if (c != 'r' || next() != 'u' || next() != 'e')
                        throw new MalformedJsonException("Invalid JSON token: expected 'true' keyword.");
                    next();
                    token = factory().bool(Boolean.TRUE);
                }
                case 'f' -> {
                    if (c != 'a' || next() != 'l' || next() != 's' || next() != 'e')
                        throw new MalformedJsonException("Invalid JSON token: expected 'false' keyword.");
                    next();
                    token = factory().bool(Boolean.FALSE);
                }
                case 'n' -> {
                    if (c != 'u' || next() != 'l' || next() != 'l')
                        throw new MalformedJsonException("Invalid JSON token: expected 'null' keyword.");
                    next();
                    token = nil();
                }
                default -> {
                    c = it.previous();
                    if (Character.isDigit(c) || c == '-') {
                        token = readNumber();
                    } else throw new MalformedJsonException("Invalid JSON near position: " + it.getIndex());
                }
            }
            return (T) token;
        }

        private String readObjectKey() {
            Object key = read();
            if (key == null)
                throw new MalformedJsonException("Missing object key (don't forget to put quotes!).");
            else if (key == OBJECT_END)
                return null;
            else if (PUNCTUATION.contains(key))
                throw new MalformedJsonException("Missing object key, found: " + key);
            else
                return ((Json) key).asString();
        }

        private Json readObject() {
            Json ret = object();
            String key = readObjectKey();
            while (token != OBJECT_END) {
                expected(COLON, read()); // should be a colon
                if (token != OBJECT_END) {
                    Json value = read();
                    ret.set(key, value);
                    if (read() == COMMA) {
                        key = readObjectKey();
                        if (key == null || PUNCTUATION.contains(key))
                            throw new MalformedJsonException("Expected a property name, but found: " + key);
                    } else
                        expected(OBJECT_END, token);
                }
            }
            return ret;
        }

        private Json readArray() {
            Json ret = array();
            Object value = read();
            while (token != ARRAY_END) {
                if (PUNCTUATION.contains(value))
                    throw new MalformedJsonException("Expected array element, but found: " + value);
                ret.add((Json) value);
                if (read() == COMMA) {
                    value = read();
                    if (value == ARRAY_END)
                        throw new MalformedJsonException("Expected array element, but found end of array after command.");
                } else
                    expected(ARRAY_END, token);
            }
            return ret;
        }

        private Json readNumber() {
            int length = 0;
            boolean isFloatingPoint = false;
            buf.setLength(0);

            if (c == '-') {
                add();
            }
            length += addDigits();
            if (c == '.') {
                add();
                length += addDigits();
                isFloatingPoint = true;
            }
            if (c == 'e' || c == 'E') {
                add();
                if (c == '+' || c == '-') {
                    add();
                }
                addDigits();
                isFloatingPoint = true;
            }

            String s = buf.toString();
            Number n = isFloatingPoint
                    ? (length < 17) ? Double.valueOf(s) : new BigDecimal(s)
                    : (length < 20) ? Long.valueOf(s) : new BigInteger(s);
            return factory().number(n);
        }

        private int addDigits() {
            int ret;
            for (ret = 0; Character.isDigit(c); ++ret) {
                add();
            }
            return ret;
        }

        private Json readString() {
            buf.setLength(0);
            while (c != '"') {
                if (c == '\\') {
                    next();
                    if (c == 'u') {
                        add(unicode());
                    } else {
                        Character value = escapes.get(c);
                        if (value != null) {
                            add(value);
                        }
                    }
                } else {
                    add();
                }
            }
            next();
            return factory().string(buf.toString());
        }

        private void add(char cc) {
            buf.append(cc);
            next();
        }

        private void add() {
            add(c);
        }

        private char unicode() {
            int value = 0;
            for (int i = 0; i < 4; ++i) {
                switch (next()) {
                    case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> value = (value << 4) + c - '0';
                    case 'a', 'b', 'c', 'd', 'e', 'f' -> value = (value << 4) + (c - 'a') + 10;
                    case 'A', 'B', 'C', 'D', 'E', 'F' -> value = (value << 4) + (c - 'A') + 10;
                }
            }
            return (char) value;
        }
    }
    // END Reader
}
