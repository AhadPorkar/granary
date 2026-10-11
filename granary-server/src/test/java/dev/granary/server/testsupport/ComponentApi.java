package dev.granary.server.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The component browser and search as the web UI uses them ({@code /api/v1/repositories/{name}/components},
 * {@code /api/v1/search}), for tests that check what a repository lists after clients used it.
 */
public final class ComponentApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final String authorization;

    /** @param authorization the {@code Authorization} header of a user who may manage repositories */
    public ComponentApi(MockMvc mvc, String authorization) {
        this.mvc = mvc;
        this.authorization = authorization;
    }

    /** The components of a repository, in the browser's order (namespace, name, version). */
    public List<JsonNode> list(String repository) throws Exception {
        return items(get("/api/v1/repositories/" + repository + "/components").param("size", "500"));
    }

    /** The components of a repository whose name or namespace contains {@code q}. */
    public List<JsonNode> list(String repository, String q) throws Exception {
        return items(get("/api/v1/repositories/" + repository + "/components").param("q", q).param("size", "500"));
    }

    /** Components of every repository whose name or namespace contains {@code q}. */
    public List<JsonNode> search(String q) throws Exception {
        return items(get("/api/v1/search").param("q", q).param("size", "500"));
    }

    /** The only component of a repository; fails when there is none or more than one. */
    public JsonNode single(String repository) throws Exception {
        List<JsonNode> all = list(repository);
        if (all.size() != 1) {
            throw new AssertionError("expected exactly one component in " + repository + " but found " + all);
        }
        return all.getFirst();
    }

    /** The paths of the assets linked to a component, sorted. */
    public List<String> assetPaths(String repository, JsonNode component) throws Exception {
        String body = mvc.perform(get("/api/v1/repositories/" + repository + "/components/" + id(component) + "/assets")
                        .header("Authorization", authorization))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> paths = new ArrayList<>();
        for (JsonNode asset : JSON.readTree(body)) {
            paths.add(asset.path("path").asString());
        }
        paths.sort(null);
        return paths;
    }

    /** Deletes a component through the API; returns the number of assets deleted with it. */
    public int delete(String repository, JsonNode component) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.delete("/api/v1/repositories/" + repository + "/components/" + id(component))
                        .header("Authorization", authorization))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("deletedAssets").asInt();
    }

    /** {@code namespace:name:version} of a component, for compact assertions. */
    public static String gav(JsonNode component) {
        return component.path("namespace").asString() + ":" + component.path("name").asString() + ":"
                + component.path("version").asString();
    }

    public static String id(JsonNode component) {
        return component.path("id").asString();
    }

    /**
     * Runs {@code threads} tasks at the same instant (every thread waits at a start gate) and returns their results in
     * task order. A task that throws fails the whole run.
     */
    public static <T> List<T> concurrently(int threads, IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<T> work = task.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return work.call();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("threads did not start");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<JsonNode> items(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.header("Authorization", authorization))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<JsonNode> items = new ArrayList<>();
        JSON.readTree(body).path("items").forEach(items::add);
        return items;
    }
}
