package baritone.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ConfigManager {

    /** "ollama" or "openai" */
    public String provider = "ollama";

    /**
     * Base URL for the LLM provider.
     *  Ollama (local):  http://localhost:11434
     *  Ollama (remote): http://192.168.1.x:11434
     *  OpenAI:          https://api.openai.com
     *  Any OpenAI-compat (LM Studio, vLLM, etc): http://host:port
     *
     * The correct chat path is appended automatically based on provider:
     *   ollama  → {endpoint}/api/chat
     *   openai  → {endpoint}/v1/chat/completions
     */
    public String endpoint = "http://localhost:11434";

    /** Model name sent in the request body. */
    public String model = "llama3";

    /** API key — required for OpenAI, leave blank for Ollama. */
    public String api_key = "";

    /** "autonomous", "puppet", or "both" */
    public String mode = "both";

    /** Port for the puppet WebSocket server. */
    public int puppet_port = 9375;

    // ---------------------------------------------------------------

    private static ConfigManager INSTANCE;

    public static ConfigManager loadOrCreate() {
        if (INSTANCE != null) return INSTANCE;

        File configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config").toFile();
        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        File configFile = new File(configDir, "llm_bridge.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                INSTANCE = gson.fromJson(reader, ConfigManager.class);
                return INSTANCE;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Write defaults on first run
        INSTANCE = new ConfigManager();
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return INSTANCE;
    }

    public static ConfigManager getInstance() {
        if (INSTANCE == null) return loadOrCreate();
        return INSTANCE;
    }

    /** Builds the full chat completion URL from the base endpoint + provider path. */
    public String getChatUrl() {
        String base = endpoint.replaceAll("/+$", ""); // strip trailing slashes
        if ("openai".equalsIgnoreCase(provider)) {
            return base + "/v1/chat/completions";
        } else {
            // ollama and any ollama-compatible server
            return base + "/api/chat";
        }
    }

    public boolean isOpenAI() {
        return "openai".equalsIgnoreCase(provider);
    }
}
