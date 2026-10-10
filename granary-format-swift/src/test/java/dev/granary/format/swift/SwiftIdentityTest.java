package dev.granary.format.swift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SwiftIdentityTest {

    @Test
    void validScopes() {
        assertThat(SwiftIdentity.isValidScope("mona")).isTrue();
        assertThat(SwiftIdentity.isValidScope("Mona-Lisa")).isTrue();
        assertThat(SwiftIdentity.isValidScope("a")).isTrue();
        assertThat(SwiftIdentity.isValidScope("1st")).isTrue();
        assertThat(SwiftIdentity.isValidScope("a".repeat(39))).isTrue();
    }

    @Test
    void invalidScopes() {
        assertThat(SwiftIdentity.isValidScope(null)).isFalse();
        assertThat(SwiftIdentity.isValidScope("")).isFalse();
        assertThat(SwiftIdentity.isValidScope("a".repeat(40))).isFalse();
        assertThat(SwiftIdentity.isValidScope("-mona")).isFalse();
        assertThat(SwiftIdentity.isValidScope("mona-")).isFalse();
        assertThat(SwiftIdentity.isValidScope("mo--na")).isFalse();
        assertThat(SwiftIdentity.isValidScope("mo_na")).isFalse();
        assertThat(SwiftIdentity.isValidScope("mo.na")).isFalse();
        assertThat(SwiftIdentity.isValidScope("möna")).isFalse();
    }

    @Test
    void validAndInvalidNames() {
        assertThat(SwiftIdentity.isValidName("LinkedList")).isTrue();
        assertThat(SwiftIdentity.isValidName("linked_list-2")).isTrue();
        assertThat(SwiftIdentity.isValidName("a".repeat(100))).isTrue();
        assertThat(SwiftIdentity.isValidName("a".repeat(101))).isFalse();
        assertThat(SwiftIdentity.isValidName("_linked")).isFalse();
        assertThat(SwiftIdentity.isValidName("linked_")).isFalse();
        assertThat(SwiftIdentity.isValidName("linked__list")).isFalse();
        assertThat(SwiftIdentity.isValidName("linked-_list")).isFalse();
        assertThat(SwiftIdentity.isValidName("linked.list")).isFalse();
        assertThat(SwiftIdentity.isValidName("")).isFalse();
    }

    @Test
    void parseSplitsAtTheOnlyDot() {
        SwiftIdentity id = SwiftIdentity.parse(" mona.LinkedList ").orElseThrow();
        assertThat(id.scope()).isEqualTo("mona");
        assertThat(id.name()).isEqualTo("LinkedList");
        assertThat(id.id()).isEqualTo("mona.LinkedList");
        assertThat(id).hasToString("mona.LinkedList");
        assertThat(SwiftIdentity.parse("mona")).isEmpty();
        assertThat(SwiftIdentity.parse(".LinkedList")).isEmpty();
        assertThat(SwiftIdentity.parse("mona.")).isEmpty();
        assertThat(SwiftIdentity.parse("mona.linked.list")).isEmpty();
        assertThat(SwiftIdentity.parse("mo_na.list")).isEmpty();
        assertThat(SwiftIdentity.parse(null)).isEmpty();
    }

    @Test
    void identitiesAreCaseInsensitive() {
        SwiftIdentity a = new SwiftIdentity("Mona", "LinkedList");
        SwiftIdentity b = new SwiftIdentity("mona", "linkedlist");
        assertThat(a.key()).isEqualTo("mona.linkedlist");
        assertThat(a.lowerScope()).isEqualTo("mona");
        assertThat(a.lowerName()).isEqualTo("linkedlist");
        assertThat(a.sameAs(b)).isTrue();
        assertThat(a.sameAs(new SwiftIdentity("mona", "tree"))).isFalse();
        assertThat(a.sameAs(null)).isFalse();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void constructorRejectsInvalidParts() {
        assertThatThrownBy(() -> new SwiftIdentity("-x", "y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SwiftIdentity("x", "y.z")).isInstanceOf(IllegalArgumentException.class);
    }
}
