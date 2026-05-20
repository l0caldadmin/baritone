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

    /** Base URL for recipe static assets and metrics. */
    public String recipe_cdn_url = "https://cdn.baritone-llm.com/recipes/";

    /** The system prompt sent to the LLM to define its behavior and tool usage. */
    public String system_prompt = "You are an autonomous AI agent controlling a Minecraft bot via Baritone. " +
            "Your goal is to fulfill user requests by planning and executing multiple steps. " +
            "STRATEGY: You must think step-by-step. Before gathering a resource, check your inventory (`mc_inventory`) to see if you have the necessary tools.\n" +
            "TASK_MEMORY: You are provided with a `TASK_MEMORY` JSON block. This is your persistent memory. If an `active_task` is present, you MUST prioritize completing it before starting new ones unless the user explicitly redirects you.\n" +
            "TASK COMMITMENT: Once you start a long-running task (mc_goto, mc_mine, mc_follow, mc_explore, mc_get_to_block, mc_find_village), YOU MUST STOP and wait for an 'Event notification'. Do NOT call any more tools until the system notifies you of success, failure, or cancellation. Trust your path and commit to the journey.\n" +
            "COMMUNICATION: Use `mc_chat` to inform the user of your plan before executing long-running tasks.\n" +
            "RESILIENCE: If a task is canceled or fails, analyze the event notification, check your surroundings, and decide if you should retry, try a different path, or ask the user for help.";

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
                RecipeService.setBaseUrl(INSTANCE.recipe_cdn_url);
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
