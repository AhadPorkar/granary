package dev.granary.core.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Persists a {@code Map<String, String>} as a JSON object in a text column. */
@Converter
public class StringMapConverter implements AttributeConverter<Map<String, String>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return MAPPER.writeValueAsString(new TreeMap<>(attribute));
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(MAPPER.readValue(dbData, TYPE));
    }
}
