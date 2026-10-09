package dev.granary.core.format;

import dev.granary.core.model.RepositoryType;
import java.util.EnumSet;
import java.util.Set;

/**
 * Describes one format-specific repository setting so the UI can render an editor for it and the server can
 * validate it. Values are stored as strings in {@link dev.granary.core.model.Repository#getFormatSettings()}.
 *
 * @param key          setting key, e.g. {@code docker.defaultRepository}
 * @param label        short English label
 * @param type         value type
 * @param defaultValue default, shown as a placeholder; {@code null} when there is none
 * @param description  one-sentence help text
 * @param appliesTo    repository types the setting is meaningful for
 */
public record SettingDescriptor(String key, String label, Type type, String defaultValue, String description,
                                Set<RepositoryType> appliesTo) {

    public enum Type { STRING, BOOLEAN, INTEGER, URL }

    public static SettingDescriptor string(String key, String label, String defaultValue, String description) {
        return new SettingDescriptor(key, label, Type.STRING, defaultValue, description, EnumSet.allOf(RepositoryType.class));
    }

    public static SettingDescriptor bool(String key, String label, boolean defaultValue, String description) {
        return new SettingDescriptor(key, label, Type.BOOLEAN, Boolean.toString(defaultValue), description,
                EnumSet.allOf(RepositoryType.class));
    }

    public static SettingDescriptor integer(String key, String label, Integer defaultValue, String description) {
        return new SettingDescriptor(key, label, Type.INTEGER, defaultValue == null ? null : defaultValue.toString(),
                description, EnumSet.allOf(RepositoryType.class));
    }

    public static SettingDescriptor url(String key, String label, String defaultValue, String description) {
        return new SettingDescriptor(key, label, Type.URL, defaultValue, description, EnumSet.allOf(RepositoryType.class));
    }

    public SettingDescriptor forTypes(RepositoryType first, RepositoryType... rest) {
        return new SettingDescriptor(key, label, type, defaultValue, description, EnumSet.of(first, rest));
    }
}
