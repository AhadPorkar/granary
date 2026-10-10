package dev.granary.format.rubygems;

import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.error.YAMLException;

/** Reads {@code checksums.yaml} of a gem: {@code {SHA256: {metadata.gz: hex, data.tar.gz: hex}, SHA512: {...}}}. */
final class ChecksumsYaml {

    private ChecksumsYaml() {
    }

    static Map<String, Map<String, String>> parse(String yaml) {
        Object root;
        try {
            root = GemspecYaml.yaml().load(yaml);
        } catch (YAMLException | IllegalArgumentException | ClassCastException | StackOverflowError e) {
            throw new InvalidGemException("The gem's checksums.yaml.gz is not valid YAML", e);
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (!(root instanceof Map<?, ?> sections)) {
            return result;
        }
        for (Map.Entry<?, ?> section : sections.entrySet()) {
            if (section.getKey() instanceof String algorithm && section.getValue() instanceof Map<?, ?> files) {
                Map<String, String> sums = new LinkedHashMap<>();
                for (Map.Entry<?, ?> file : files.entrySet()) {
                    if (file.getKey() instanceof String name && file.getValue() instanceof String sum) {
                        sums.put(name, sum);
                    }
                }
                result.put(algorithm, sums);
            }
        }
        return result;
    }
}
