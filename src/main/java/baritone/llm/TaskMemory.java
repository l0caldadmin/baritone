package baritone.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Persists high-level task state and "memory" across LLM turns.
 * This helps the LLM remember long-term goals even if the conversation history is pruned.
 */
public class TaskMemory {

    private static final String MEMORY_PATH = "_logs/task_memory.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_SIZE_BYTES = 2048; // Strict 2KB limit

    public static synchronized void updateMemory(TaskRegistry.Task activeTask) {
        try {
            JsonObject memory = new JsonObject();
            
            if (activeTask != null) {
                memory.addProperty("task", activeTask.type);
                memory.addProperty("phase", activeTask.phase.toString());
                memory.addProperty("prog", activeTask.currentCount + "/" + activeTask.targetQuantity);
                if (activeTask.lockedTarget != null) {
                    memory.addProperty("loc", activeTask.lockedTarget.getX() + "," + activeTask.lockedTarget.getY() + "," + activeTask.lockedTarget.getZ());
                }
                memory.addProperty("age_ms", System.currentTimeMillis() - activeTask.startTime);
            } else {
                memory.addProperty("task", "none");
            }

            // Optional: Maintain a "long-term facts" section if needed
            // For now, we just track the active task as requested.

            String json = GSON.toJson(memory);
            
            // Check size limit
            if (json.getBytes().length > MAX_SIZE_BYTES) {
                AutonomousLogger.log("MEMORY_WARN", "Task memory exceeded size limit, pruning...");
                // (Pruning logic could go here, but for 1 task it won't exceed 2KB)
            }

            File dir = new File("_logs");
            if (!dir.exists()) dir.mkdirs();

            try (FileWriter writer = new FileWriter(MEMORY_PATH)) {
                writer.write(json);
            }
        } catch (Exception e) {
            AutonomousLogger.log("MEMORY_ERROR", "Failed to save task memory: " + e.getMessage());
        }
    }

    public static synchronized String getMemoryAsContext() {
        try {
            File file = new File(MEMORY_PATH);
            if (!file.exists()) return "{}";
            
            return new String(Files.readAllBytes(Paths.get(MEMORY_PATH)));
        } catch (Exception e) {
            return "{}";
        }
    }
}
