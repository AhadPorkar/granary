package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.hosted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConanComponentsTest {

    private final StorageService storage = mock(StorageService.class);
    private final Repository repo = hosted("conan-hosted");
    private final Component component = mock(Component.class);

    @Test
    void coordinatesOfAReference() {
        assertThat(ConanComponents.coordinates(new ConanReference("zlib", "1.3.1", null, null)))
                .isEqualTo(new StorageService.ComponentCoordinates("", "zlib", "1.3.1"));
        assertThat(ConanComponents.coordinates(new ConanReference("zlib", "1.3.1", "team", "stable")))
                .isEqualTo(new StorageService.ComponentCoordinates("team/stable", "zlib", "1.3.1"));
    }

    @Test
    void aLostRaceFindsTheComponentTheOtherRequestCreated() {
        StorageService.ComponentCoordinates zlib = new StorageService.ComponentCoordinates("", "zlib", "1.3.1");
        when(storage.ensureComponent(repo, zlib))
                .thenThrow(new DataIntegrityViolationException("uk_component_coordinates"))
                .thenReturn(component);
        assertThat(ConanComponents.ensure(storage, repo, zlib)).isSameAs(component);
        verify(storage, times(2)).ensureComponent(repo, zlib);
    }

    @Test
    void anythingElseIsNotRetried() {
        StorageService.ComponentCoordinates zlib = new StorageService.ComponentCoordinates("", "zlib", "1.3.1");
        when(storage.ensureComponent(repo, zlib)).thenThrow(new IllegalStateException("database is down"));
        assertThatThrownBy(() -> ConanComponents.ensure(storage, repo, zlib)).isInstanceOf(IllegalStateException.class);
        verify(storage, times(1)).ensureComponent(repo, zlib);
    }

    @Test
    void aSecondFailureIsReported() {
        StorageService.ComponentCoordinates zlib = new StorageService.ComponentCoordinates("", "zlib", "1.3.1");
        when(storage.ensureComponent(repo, zlib)).thenThrow(new DataIntegrityViolationException("still failing"));
        assertThatThrownBy(() -> ConanComponents.ensure(storage, repo, zlib))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(storage, times(2)).ensureComponent(repo, zlib);
    }
}
