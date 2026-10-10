package dev.granary.format.go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GoModFileTest {

    @Test
    void findsTheModuleDirective() {
        assertThat(GoModFile.modulePath("module example.com/x\n\ngo 1.21\n")).contains("example.com/x");
        assertThat(GoModFile.modulePath("// a comment\n\n   module   example.com/x   // trailing\ngo 1.21\n")).contains("example.com/x");
        assertThat(GoModFile.modulePath("module \"example.com/x\"\n")).contains("example.com/x");
        assertThat(GoModFile.modulePath("module `example.com/x`\n")).contains("example.com/x");
        assertThat(GoModFile.modulePath("module example.com/x\r\ngo 1.21\r\n")).contains("example.com/x");
        assertThat(GoModFile.modulePath("go 1.21\nmodule example.com/x\n")).contains("example.com/x");
        assertThat(GoModFile.modulePath("module example.com/x\nmodule example.com/y\n")).contains("example.com/x");
    }

    @Test
    void missingDirective() {
        assertThat(GoModFile.modulePath("go 1.21\n")).isEmpty();
        assertThat(GoModFile.modulePath("// module example.com/x\n")).isEmpty();
        assertThat(GoModFile.modulePath("modulex example.com/x\n")).isEmpty();
        assertThat(GoModFile.modulePath("module\n")).isEmpty();
        assertThat(GoModFile.modulePath("")).isEmpty();
        assertThat(GoModFile.modulePath((String) null)).isEmpty();
        assertThat(GoModFile.modulePath((byte[]) null)).isEmpty();
    }

    @Test
    void checksTheDeclaredPath() {
        GoModFile.checkDeclaredPath("module example.com/x\n".getBytes(StandardCharsets.UTF_8), "example.com/x");
        assertThatThrownBy(() -> GoModFile.checkDeclaredPath("module example.com/y\n".getBytes(StandardCharsets.UTF_8), "example.com/x"))
                .isInstanceOf(GoError.class).hasMessageContaining("declares its path as \"example.com/y\"");
        assertThatThrownBy(() -> GoModFile.checkDeclaredPath("go 1.21\n".getBytes(StandardCharsets.UTF_8), "example.com/x"))
                .isInstanceOf(GoError.class).hasMessageContaining("no module directive");
        assertThatThrownBy(() -> GoModFile.checkDeclaredPath(new byte[] {'m', 0, 'x'}, "example.com/x"))
                .isInstanceOf(GoError.class).hasMessageContaining("not a text file");
    }

    @Test
    void syntheticGoModForIncompatibleModules() {
        assertThat(new String(GoModFile.synthetic("example.com/x"), StandardCharsets.UTF_8)).isEqualTo("module example.com/x\n");
    }
}
