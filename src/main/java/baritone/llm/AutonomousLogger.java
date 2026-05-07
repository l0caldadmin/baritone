package baritone.llm;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AutonomousLogger {

    private static Path logFile;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static {
        try {
            Path baritoneDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("baritone");
            Path logsDir = baritoneDir.resolve("logs");
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }
            logFile = logsDir.resolve("llm.log");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void log(String tag, String message) {
        if (logFile == null) return;
        
        try (FileWriter fw = new FileWriter(logFile.toFile(), true);
             PrintWriter pw = new PrintWriter(fw)) {
            String timestamp = LocalDateTime.now().format(formatter);
            pw.println("[" + timestamp + "] [" + tag + "] " + message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void logRequest(String body) {
        log("REQUEST", body);
    }

    public static void logResponse(String body) {
        log("RESPONSE", body);
    }

    public static void logAction(String action, String args) {
        log("ACTION", action + " with args: " + args);
    }

    public static void logEvent(String event) {
        log("EVENT", event);
    }
}
