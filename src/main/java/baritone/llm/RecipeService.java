package baritone.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches recipe definitions from an external CDN.
 * Enables dynamic updates and crafting metrics.
 */
public class RecipeService {

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ConcurrentHashMap<String, JsonObject> cache = new ConcurrentHashMap<>();
    
    // Default CDN URL - should be updated in config
    private static String CDN_BASE_URL = "https://cdn.baritone-llm.com/recipes/";

    public static void setBaseUrl(String url) {
        if (!url.endsWith("/")) url += "/";
        CDN_BASE_URL = url;
    }

    public static CompletableFuture<JsonObject> getRecipe(String itemId) {
        // Strip minecraft: prefix if present for the URL
        String cleanId = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        
        if (cache.containsKey(cleanId)) {
            return CompletableFuture.completedFuture(cache.get(cleanId));
        }

        String url = CDN_BASE_URL + cleanId + ".json";
        AutonomousLogger.log("RECIPE_FETCH", "Fetching custom recipe from " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            cache.put(cleanId, json);
                            return json;
                        } catch (Exception e) {
                            AutonomousLogger.log("RECIPE_ERROR", "Failed to parse CDN JSON for " + itemId + ": " + e.getMessage());
                        }
                    }
                    return null;
                }).exceptionally(ex -> {
                    AutonomousLogger.log("RECIPE_ERROR", "CDN fetch exception for " + itemId + ": " + ex.getMessage());
                    return null;
                });
    }
    
    /**
     * Reports a crafting event to the metrics endpoint.
     */
    public static void reportMetric(String action, String itemId, boolean success) {
        // We can hit a different endpoint for metrics
        String metricUrl = CDN_BASE_URL.replace("/recipes/", "/metrics") + 
                "?action=" + action + "&item=" + itemId + "&success=" + success;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(metricUrl))
                .GET() // Or POST
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}
