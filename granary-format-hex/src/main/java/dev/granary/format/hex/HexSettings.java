package dev.granary.format.hex;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** The format-specific repository settings of Hex repositories and how they are read and validated. */
public final class HexSettings {

    /** The name clients register the repository under; the signed registry documents carry it. */
    public static final String REPOSITORY_NAME = "hex.repositoryName";
    /** How long after publishing a release may still be reverted ({@code mix hex.publish --revert}). */
    public static final String REVERT_WINDOW = "hex.revertWindowMinutes";
    /** Whether a group checks the signatures of proxied registry documents before re-signing them. */
    public static final String VERIFY_UPSTREAM = "hex.verifyUpstreamSignatures";

    public static final int DEFAULT_REVERT_WINDOW_MINUTES = 60;

    /** Hex repository names: what {@code mix hex.repo add NAME} and {@code repo: "NAME"} accept, minus the ':' organisations use. */
    static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$");

    private HexSettings() {
    }

    static List<SettingDescriptor> descriptors() {
        return List.of(
                SettingDescriptor.string(REPOSITORY_NAME, "Hex repository name", null,
                                "The name clients give this repository (mix hex.repo add NAME, repo: \"NAME\", rebar3 "
                                        + "repos name); the signed registry documents carry it and clients refuse a "
                                        + "mismatch. Defaults to the Granary repository name.")
                        .forTypes(RepositoryType.HOSTED, RepositoryType.GROUP),
                SettingDescriptor.integer(REVERT_WINDOW, "Revert window (minutes)", DEFAULT_REVERT_WINDOW_MINUTES,
                                "How long after publishing a release may still be reverted with mix hex.publish --revert; "
                                        + "0 allows reverting at any time. Administrators may always revert.")
                        .forTypes(RepositoryType.HOSTED),
                SettingDescriptor.bool(VERIFY_UPSTREAM, "Verify upstream signatures", true,
                                "Check the signature of every registry document a proxy member returns, with that proxy's "
                                        + "public_key, before a group merges and re-signs it.")
                        .forTypes(RepositoryType.GROUP));
    }

    /** The repository name the registry documents of a hosted repository or group carry. */
    public static String repositoryName(Repository repo) {
        return repo.setting(REPOSITORY_NAME).map(String::trim).filter(s -> !s.isEmpty()).orElse(repo.getName());
    }

    /** The revert window; {@link Optional#empty()} means "no limit". */
    static Optional<Duration> revertWindow(Repository repo) {
        int minutes;
        try {
            minutes = parseWindow(repo.setting(REVERT_WINDOW).orElse(null));
        } catch (IllegalArgumentException e) {
            minutes = DEFAULT_REVERT_WINDOW_MINUTES; // validated on save; never let a stray value open the window
        }
        return minutes == 0 ? Optional.empty() : Optional.of(Duration.ofMinutes(minutes));
    }

    static boolean verifyUpstream(Repository repo) {
        return repo.settingEnabled(VERIFY_UPSTREAM, true);
    }

    static void validate(Repository repo) {
        repo.setting(REPOSITORY_NAME).map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            if (!REPOSITORY_NAME_PATTERN.matcher(name).matches()) {
                throw GranaryException.badRequest("repository.hex.repositoryName.invalid",
                        REPOSITORY_NAME + " must be 1-100 letters, digits, '_', '-' or '.', starting with a letter or digit");
            }
        });
        repo.setting(REVERT_WINDOW).ifPresent(value -> {
            try {
                parseWindow(value);
            } catch (IllegalArgumentException e) {
                throw GranaryException.badRequest("repository.hex.revertWindow.invalid",
                        REVERT_WINDOW + " must be a whole number of minutes, 0 or more");
            }
        });
        repo.setting(VERIFY_UPSTREAM).ifPresent(value -> {
            String v = value.trim();
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                throw GranaryException.badRequest("repository.hex.verifyUpstreamSignatures.invalid",
                        VERIFY_UPSTREAM + " must be true or false");
            }
        });
    }

    private static int parseWindow(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_REVERT_WINDOW_MINUTES;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + value);
        }
        if (minutes < 0) {
            throw new IllegalArgumentException("negative: " + value);
        }
        return minutes;
    }
}
