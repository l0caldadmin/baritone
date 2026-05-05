package baritone.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ConfigManager {
    public String mode = "both"; // "autonomous", "puppet", or "both"
    public String autonomous_endpoint = "http://localhost:11434/api/chat";
    public int puppet_port = 9375;

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
}
