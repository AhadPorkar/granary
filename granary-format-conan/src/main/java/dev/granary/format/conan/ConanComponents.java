package dev.granary.format.conan;

import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The component of a Conan reference (one per repository and {@code name/version@user/channel}), created safely
 * under concurrency.
 *
 * <p>Conan transfers the files of a revision in parallel: downloads always, uploads with
 * {@code core.upload:parallel}. The first requests for a new reference therefore all look for its component, find
 * none and insert it; the database accepts one insert and refuses the others. A refused request then finds the
 * component the winner created, so every file of the reference ends up linked to the same component.
 */
final class ConanComponents {

    private ConanComponents() {
    }

    static StorageService.ComponentCoordinates coordinates(ConanReference ref) {
        return new StorageService.ComponentCoordinates(ref.namespace(), ref.name(), ref.version());
    }

    /** Finds or creates the component; a lost race with a parallel request yields the component that request created. */
    static Component ensure(StorageService storage, Repository repo, StorageService.ComponentCoordinates coordinates) {
        try {
            return storage.ensureComponent(repo, coordinates);
        } catch (DataIntegrityViolationException raced) {
            // the other insert has been committed by now: this lookup finds it
            return storage.ensureComponent(repo, coordinates);
        }
    }
}
