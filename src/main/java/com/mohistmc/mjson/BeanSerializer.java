package com.mohistmc.mjson;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializer bean to data object
 *
 * @author Fan Wen Jie
 * @version 2015-03-05
 */
public class BeanSerializer {

    /**
     * Serializer bean to data object combined by values whose type are null String Number Boolean Map Collection Array Map
     *
     * @param bean the bean which will be serializer
     * @return the data object made from bean
     * @throws NullPointerException thrown when value of the necessary field of bean is null
     */
    public static Object serialize(Object bean) throws NullPointerException {
        if (bean == null)
            return null;
        if (bean instanceof String || bean instanceof Boolean || bean instanceof Number)
            return bean;
        if (bean instanceof Collection)
            return serialize(((Collection<?>) bean).toArray());
        if (bean.getClass().isArray()) {
            int length = Array.getLength(bean);
            ArrayList<Object> array = new ArrayList<>(length);
            for (int i = 0; i < length; ++i)
                array.add(serialize(Array.get(bean, i)));
            return array;
        }
        if (bean instanceof Map map) {
            map.replaceAll((k, v) -> serialize(map.get(k)));
            return map;
        }

        ArrayList<Integer> indexs = new ArrayList<>();
        ArrayList<Object> values = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();
        for (Field field : bean.getClass().getDeclaredFields()) {
            boolean isRequired = false;
            Object value = null;
            try {
                field.setAccessible(true);
                ToJson seriable = field.getAnnotation(ToJson.class);
                if (seriable != null) {
                    String key = field.getName();
                    value = serialize(field.get(bean));
                    isRequired = seriable.isRequired();
                    if (!seriable.name().isEmpty())
                        key = seriable.name();
                    int order = seriable.order();
                    int positon = indexs.size();
                    if (order < Integer.MAX_VALUE)
                        for (int i = 0; i < indexs.size(); ++i)
                            if (order < indexs.get(i))
                                positon = i;
                    indexs.add(positon, order);
                    values.add(positon, value);
                    keys.add(positon, key);
                }
            } catch (Exception ignore) {
            } finally {
                if (isRequired && null == value)
                    throw new NullPointerException("Field " + field.getName() + " can't be null");
            }
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>(indexs.size());
        for (int i = 0; i < indexs.size(); ++i)
            map.put(keys.get(i), values.get(i));
        return map;
    }

    public static <T> T deserialize(Class<T> klass, Map map) throws Exception {
        T bean = klass.newInstance();
        for (Field field : klass.getDeclaredFields()) {
            Object value = null;
            boolean isRequired = false;
            try {
                field.setAccessible(true);
                ToJson seriable = field.getAnnotation(ToJson.class);
                if (seriable != null) {
                    String name = seriable.name();
                    if (name.isEmpty())
                        name = field.getName();
                    isRequired = seriable.isRequired();
                    value = map.get(name);

                    Class clazz = field.getType();
                    if (Collection.class.isAssignableFrom(clazz)) {
                        Class genericType = Object.class;
                        try {
                            genericType = (Class) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                        } catch (Exception ignore) {
                        }
                        if (value instanceof Collection)
                            value = deserialize(clazz, genericType, (Collection) value);
                        else if (value.getClass().isArray())
                            value = deserialize(clazz, genericType, (Object[]) value);
                        else return null;
                    } else
                        value = deserialize(clazz, value);
                    field.set(bean, value);
                }
            } catch (Exception ignore) {
            } finally {
                if (isRequired && value == null)
                    throw new NullPointerException();
            }
        }
        return bean;
    }

    public static <T, A> Collection<T> deserialize(Class<? extends Collection> klass, Class<T> genericType, A[] array) throws Exception {
        Collection collection = klass.newInstance();
        for (A a : array) collection.add(deserialize(genericType, a));
        return collection;
    }

    public static <T> Collection<T> deserialize(Class<? extends Collection> klass, Class<T> genericType, Collection array) throws Exception {
        return deserialize(klass, genericType, array.toArray());
    }

    public static <T> T[] deserialize(Class<T> componentType, Collection array) {
        return deserialize(componentType, array.toArray());
    }

    public static <T, A> T[] deserialize(Class<T> componentType, A[] array) {
        T[] collection = (T[]) (Array.newInstance(componentType, array.length));
        for (int i = 0; i < array.length; ++i)
            collection[i] = deserialize(componentType, array[i]);
        return collection;
    }


    public static <T> T deserialize(Class<T> klass, Object object) {
        try {
            if (object instanceof Number || object instanceof String || object instanceof Boolean) {
                return (T) object;
            } else if (object instanceof Map) {
                if (Map.class.isAssignableFrom(klass))
                    return klass.cast(object);
                else
                    return deserialize(klass, (Map) object);
            } else if (Collection.class.isAssignableFrom(klass)) {
                if (object instanceof Collection)
                    return (T) deserialize((Class<? extends Collection>) klass, Object.class, (Collection) object);
                else if (object.getClass().isArray())
                    return (T) deserialize((Class<? extends Collection>) klass, Object.class, (Object[]) object);
                else return null;
            } else if (klass.isArray()) {
                if (object instanceof Collection)
                    return (T) deserialize(klass.getComponentType(), (Collection) object);
                else if (object.getClass().isArray())
                    return (T) deserialize(klass.getComponentType(), (Object[]) object);
                else return null;
            } else
                return null;
        } catch (Exception e) {
            return null;
        }
    }
}
