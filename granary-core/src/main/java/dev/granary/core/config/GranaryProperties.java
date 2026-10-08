package dev.granary.core.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All {@code granary.*} settings. Every value has a sensible default so the server starts with no
 * configuration at all; production deployments override them via environment variables
 * ({@code GRANARY_DATA_DIR}, {@code GRANARY_SECURITY_ADMIN_PASSWORD}, …).
 */
@ConfigurationProperties(prefix = "granary")
public class GranaryProperties {

    /** Root directory for blobs, the embedded database and generated secrets. */
    private Path dataDir = Path.of("data");

    private final Storage storage = new Storage();
    private final Security security = new Security();
    private final Proxy proxy = new Proxy();

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public Storage getStorage() {
        return storage;
    }

    public Security getSecurity() {
        return security;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public static class Storage {
        /** Additional named blob stores; the {@code default} store always exists. */
        private Map<String, BlobStoreProperties> blobStores = new LinkedHashMap<>();

        public Map<String, BlobStoreProperties> getBlobStores() {
            return blobStores;
        }

        public void setBlobStores(Map<String, BlobStoreProperties> blobStores) {
            this.blobStores = blobStores;
        }
    }

    public static class BlobStoreProperties {
        private Path path;

        public Path getPath() {
            return path;
        }

        public void setPath(Path path) {
            this.path = path;
        }
    }

    public static class Security {
        /** Password given to the built-in {@code admin} user when it is first created. */
        private String adminPassword = "admin123";
        /** Secret used to sign web-session tokens; generated and persisted under the data dir when empty. */
        private String jwtSecret = "";
        /** Lifetime of web-session tokens issued by the login endpoint. */
        private Duration sessionTokenTtl = Duration.ofHours(12);
        /** Whether unauthenticated clients may read repositories until an admin changes the setting. */
        private boolean anonymousReadDefault = true;

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public Duration getSessionTokenTtl() {
            return sessionTokenTtl;
        }

        public void setSessionTokenTtl(Duration sessionTokenTtl) {
            this.sessionTokenTtl = sessionTokenTtl;
        }

        public boolean isAnonymousReadDefault() {
            return anonymousReadDefault;
        }

        public void setAnonymousReadDefault(boolean anonymousReadDefault) {
            this.anonymousReadDefault = anonymousReadDefault;
        }
    }

    public static class Proxy {
        /** Default connect timeout for upstream requests (per repository override possible). */
        private Duration connectTimeout = Duration.ofSeconds(10);
        /** Default read timeout for upstream requests. */
        private Duration readTimeout = Duration.ofSeconds(60);
        /** How long cached index/metadata documents are served without asking upstream again. */
        private Duration metadataMaxAge = Duration.ofMinutes(30);
        /** How long an upstream 404 is remembered. */
        private Duration negativeCacheTtl = Duration.ofMinutes(10);
        /** After this many consecutive failures an upstream is considered down. */
        private int failureThreshold = 3;
        /** Minimum back-off before a down upstream is probed again. */
        private Duration circuitOpenMin = Duration.ofSeconds(30);
        /** Maximum back-off between probes of a down upstream. */
        private Duration circuitOpenMax = Duration.ofMinutes(10);
        /** Value sent as {@code User-Agent} to upstream registries. */
        private String userAgent = "Granary/0.1 (+https://github.com/AhadPorkar/granary)";

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getMetadataMaxAge() {
            return metadataMaxAge;
        }

        public void setMetadataMaxAge(Duration metadataMaxAge) {
            this.metadataMaxAge = metadataMaxAge;
        }

        public Duration getNegativeCacheTtl() {
            return negativeCacheTtl;
        }

        public void setNegativeCacheTtl(Duration negativeCacheTtl) {
            this.negativeCacheTtl = negativeCacheTtl;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getCircuitOpenMin() {
            return circuitOpenMin;
        }

        public void setCircuitOpenMin(Duration circuitOpenMin) {
            this.circuitOpenMin = circuitOpenMin;
        }

        public Duration getCircuitOpenMax() {
            return circuitOpenMax;
        }

        public void setCircuitOpenMax(Duration circuitOpenMax) {
            this.circuitOpenMax = circuitOpenMax;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }
}
