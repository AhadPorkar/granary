package dev.granary.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.server.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The web UI translates format display names and format setting texts from the catalogue under
 * {@code formatText} in its locale files, keyed by format id and setting key, and shows the server's
 * English text when a locale has no entry. The English entries are copies of the server text; this test
 * fails when a setting is added or reworded here without updating the catalogue in all five locales
 * ({@code granary-ui/src/locales/*.json}), and keeps the texts in the house style.
 */
class FormatTextCatalogueTest extends IntegrationTest {

    private static final Path EN = Path.of("..", "granary-ui", "src", "locales", "en.json");

    @Autowired
    private FormatRegistry formats;

    private JsonNode catalogue;

    @BeforeEach
    void readCatalogue() throws Exception {
        assumeTrue(Files.isRegularFile(EN), "the web UI sources are not next to the server module");
        catalogue = JsonMapper.builder().build().readTree(Files.readString(EN)).path("formatText");
    }

    @Test
    void everySettingHasAnEnglishCatalogueEntryMatchingTheServerText() {
        assertThat(catalogue.path("settings").isObject()).as("formatText.settings in en.json").isTrue();
        assertThat(settings()).as("settings of the installed formats").hasSizeGreaterThanOrEqualTo(25);
        List<String> problems = new ArrayList<>();
        for (SettingDescriptor setting : settings().values()) {
            JsonNode entry = node(catalogue.path("settings"), setting.key());
            if (entry.isMissingNode()) {
                problems.add(setting.key() + ": missing from formatText.settings");
                continue;
            }
            compare(problems, setting.key() + ".label", setting.label(), entry.path("label"));
            compare(problems, setting.key() + ".description", setting.description(), entry.path("description"));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theCatalogueHasNoEntriesForSettingsThatNoLongerExist() {
        Map<String, SettingDescriptor> settings = settings();
        List<String> stale = new ArrayList<>();
        catalogue.path("settings").properties().forEach(prefix -> prefix.getValue().properties().forEach(name -> {
            String key = prefix.getKey() + "." + name.getKey();
            if (!settings.containsKey(key)) {
                stale.add(key);
            }
        }));
        assertThat(stale).isEmpty();
    }

    @Test
    void translatedDisplayNamesBelongToInstalledFormats() {
        List<String> problems = new ArrayList<>();
        catalogue.path("formats").properties().forEach(entry -> {
            FormatHandler handler = formats.all().stream()
                    .filter(h -> h.format().equals(entry.getKey())).findFirst().orElse(null);
            if (handler == null) {
                problems.add(entry.getKey() + ": no such format");
            } else {
                compare(problems, entry.getKey(), handler.displayName(), entry.getValue());
            }
        });
        assertThat(problems).isEmpty();
    }

    @Test
    void settingTextsFollowTheHouseStyle() {
        List<String> problems = new ArrayList<>();
        for (SettingDescriptor setting : settings().values()) {
            if (setting.label() == null || setting.label().isBlank() || setting.label().endsWith(".")) {
                problems.add(setting.key() + ": a label is a short phrase without a full stop");
            }
            if (setting.description() == null || !setting.description().endsWith(".")) {
                problems.add(setting.key() + ": a description is a sentence ending with a full stop");
            }
        }
        assertThat(problems).isEmpty();
    }

    private Map<String, SettingDescriptor> settings() {
        Map<String, SettingDescriptor> all = new TreeMap<>();
        formats.all().forEach(handler -> handler.settings().forEach(setting -> all.put(setting.key(), setting)));
        return all;
    }

    private static JsonNode node(JsonNode root, String dottedKey) {
        JsonNode current = root;
        for (String part : dottedKey.split("\\.")) {
            current = current.path(part);
        }
        return current;
    }

    private static void compare(List<String> problems, String what, String server, JsonNode entry) {
        String ui = entry.isString() ? unescape(entry.asString()) : null;
        if (!server.equals(ui)) {
            problems.add(what + ": server says \"" + server + "\", en.json says \"" + ui + "\"");
        }
    }

    /** vue-i18n literal interpolation: {'{'} and {'}'} in the locale files stand for single braces. */
    private static String unescape(String message) {
        return message.replace("{'{'}", "{").replace("{'}'}", "}");
    }
}
