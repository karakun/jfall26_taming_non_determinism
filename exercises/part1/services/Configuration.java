package services;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A service's settings from {@code mavai-services.yaml}, read with their types checked. A setting that is
 * missing, unknown or of the wrong type stops the run before the first sample, naming the setting.
 */
final class Configuration {

    private final String service;
    private final Map<String, Object> values;
    private final Set<String> read = new LinkedHashSet<>();

    Configuration(String service, Map<String, Object> values) {
        this.service = service;
        this.values = values;
    }

    String text(String key) {
        return value(key) instanceof String text ? text : refuse(key, "text");
    }

    double number(String key) {
        return value(key) instanceof Number number ? number.doubleValue() : refuse(key, "a number");
    }

    int wholeNumber(String key) {
        return value(key) instanceof Integer whole ? whole : refuse(key, "a whole number");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> mapping(String key) {
        return value(key) instanceof Map<?, ?> mapping ? (Map<String, Object>) mapping : refuse(key, "a mapping");
    }

    /** Call once every setting has been read: anything left over is a setting this service doesn't have. */
    void refuseUnknown() {
        for (String key : values.keySet()) {
            if (!read.contains(key)) {
                throw new IllegalStateException("service '" + service + "': unknown setting `" + key + ":`");
            }
        }
    }

    private Object value(String key) {
        read.add(key);
        if (!values.containsKey(key)) {
            throw new IllegalStateException("service '" + service + "': missing setting `" + key + ":`");
        }
        return values.get(key);
    }

    private <T> T refuse(String key, String expected) {
        throw new IllegalStateException("service '" + service + "': `" + key + ":` must be " + expected
                + ", got " + values.get(key));
    }
}
