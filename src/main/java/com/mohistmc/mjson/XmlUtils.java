package com.mohistmc.mjson;

import java.util.Map;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.InputSource;


public abstract class XmlUtils {
    public static Map<?, ?> xmlToMap(InputSource in) {
        try {
            if (in == null) {
                return Map.of();
            }

            SAXHandler handler = new SAXHandler();
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.newSAXParser().parse(in, handler);

            return handler.getMap();
        } catch (Exception e) {
            return Map.of();
        }
    }
}
