package dev.granary.core.settings;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.dao.SettingDao;
import dev.granary.core.model.Setting;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runtime-editable global settings with an in-memory read cache. */
@Service
public class SettingsService {

    public static final String ANONYMOUS_READ = "security.anonymousRead";
    public static final String GLOBAL_OFFLINE = "system.offlineMode";
    public static final String BASE_URL = "system.baseUrl";

    private final SettingDao settings;
    private final GranaryProperties properties;
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public SettingsService(SettingDao settings, GranaryProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return cache.computeIfAbsent(key, k -> settings.findById(k).map(Setting::getValue));
    }

    @Transactional
    public void set(String key, String value) {
        Setting setting = settings.findById(key).orElseGet(() -> new Setting(key, value));
        setting.setValue(value);
        settings.save(setting);
        cache.put(key, Optional.ofNullable(value));
    }

    public boolean isAnonymousReadEnabled() {
        return get(ANONYMOUS_READ).map(Boolean::parseBoolean)
                .orElse(properties.getSecurity().isAnonymousReadDefault());
    }

    public void setAnonymousReadEnabled(boolean enabled) {
        set(ANONYMOUS_READ, Boolean.toString(enabled));
    }

    /** When on, every proxy repository behaves as if its own offline switch were set. */
    public boolean isGlobalOffline() {
        return get(GLOBAL_OFFLINE).map(Boolean::parseBoolean).orElse(false);
    }

    public void setGlobalOffline(boolean offline) {
        set(GLOBAL_OFFLINE, Boolean.toString(offline));
    }

    /** External base URL (scheme, host, port, optional context path) when the server sits behind a reverse proxy. */
    public Optional<String> baseUrl() {
        return get(BASE_URL).filter(v -> !v.isBlank()).map(v -> v.endsWith("/") ? v.substring(0, v.length() - 1) : v);
    }

    public void setBaseUrl(String baseUrl) {
        set(BASE_URL, baseUrl == null ? null : baseUrl.trim());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("anonymousRead", isAnonymousReadEnabled());
        map.put("offlineMode", isGlobalOffline());
        map.put("baseUrl", baseUrl().orElse(""));
        return map;
    }
}
