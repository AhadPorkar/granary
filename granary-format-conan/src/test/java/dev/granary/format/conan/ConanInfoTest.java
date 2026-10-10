package dev.granary.format.conan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ConanInfoTest {

    @Test
    void sectionsOfAssignmentsBecomeObjectsAndOtherSectionsArrays() {
        ObjectNode info = ConanInfo.parse(ConanTestSupport.CONANINFO);
        assertThat(info.propertyNames()).containsExactly("settings", "options", "requires");
        assertThat(info.path("settings").path("os").stringValue()).isEqualTo("Linux");
        assertThat(info.path("settings").path("arch").stringValue()).isEqualTo("x86_64");
        assertThat(info.path("options").path("shared").stringValue()).isEqualTo("False");
        assertThat(info.path("requires").isArray()).isTrue();
        assertThat(info.path("requires").get(0).stringValue()).isEqualTo("zlib/1.3.Z");
    }

    @Test
    void conan2LayoutWithSubSettingsAndEmptySections() {
        ObjectNode info = ConanInfo.parse("""
                [settings]
                compiler=gcc
                compiler.version=13
                compiler.libcxx=libstdc++11

                [options]

                [requires]
                """);
        assertThat(info.path("settings").path("compiler.version").stringValue()).isEqualTo("13");
        assertThat(info.path("options").isObject()).isTrue();
        assertThat(info.path("options").isEmpty()).isTrue();
        assertThat(info.path("requires").isObject()).isTrue();
    }

    @Test
    void conan1RecipeHashIsAString() {
        // the conaninfo.txt of a Conan 1.x header-only package, as conan create writes it
        ObjectNode info = ConanInfo.parse("""
                [settings]

                [requires]

                [options]

                [full_settings]

                [full_requires]

                [full_options]

                [recipe_hash]
                    21caf656bb881e9231d0f27daca53fb4

                [env]

                """);
        assertThat(info.path("recipe_hash").isString()).isTrue();
        assertThat(info.path("recipe_hash").stringValue()).isEqualTo("21caf656bb881e9231d0f27daca53fb4");
        assertThat(info.path("settings").isObject()).isTrue();
        // anything else in that section stays as it is
        assertThat(ConanInfo.parse("[recipe_hash]\na\nb\n").path("recipe_hash").isArray()).isTrue();
        assertThat(ConanInfo.parse("[recipe_hash]\nkey=value\n").path("recipe_hash").isObject()).isTrue();
    }

    @Test
    void valuesMayContainEqualsSigns() {
        ObjectNode info = ConanInfo.parse("[conf]\ntools.build:cxxflags=-DX=1\n");
        assertThat(info.path("conf").path("tools.build:cxxflags").stringValue()).isEqualTo("-DX=1");
    }

    @Test
    void mixedSectionsAreKeptAsLines() {
        ObjectNode info = ConanInfo.parse("[requires]\nzlib/1.3.Z\nkey=value\n");
        assertThat(info.path("requires").isArray()).isTrue();
        assertThat(info.path("requires")).hasSize(2);
    }

    @Test
    void windowsLineEndingsCommentsAndRepeatedSections() {
        ObjectNode info = ConanInfo.parse("# comment\r\n[settings]\r\nos=Windows\r\n[settings]\r\narch=armv8\r\n");
        assertThat(info.path("settings").path("os").stringValue()).isEqualTo("Windows");
        assertThat(info.path("settings").path("arch").stringValue()).isEqualTo("armv8");
    }

    @Test
    void malformedInputNeverFails() {
        assertThat(ConanInfo.parse(null).isEmpty()).isTrue();
        assertThat(ConanInfo.parse("").isEmpty()).isTrue();
        assertThat(ConanInfo.parse("no section\nkey=value\n").isEmpty()).isTrue();
        ObjectNode info = ConanInfo.parse("[bad section]\nx=1\n[ok]\ny=2\n[unclosed\nz=3\n");
        assertThat(info.propertyNames()).containsExactly("ok");
        assertThat(info.path("ok").path("y").stringValue()).isEqualTo("2");
        assertThat(ConanInfo.parse("[" + "x".repeat(80) + "]\na=b\n").isEmpty()).isTrue();
        assertThat(ConanInfo.parse("\u0000\u0001[settings]\n").isEmpty()).isTrue();
    }
}
