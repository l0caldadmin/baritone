package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.llm.AutonomousClient;
import baritone.llm.ConversationHistory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class BotCommand extends Command {

    protected BotCommand(IBaritone baritone) {
        super(baritone, "bot");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        String prompt = args.rawRest();
        
        if (prompt.equalsIgnoreCase("clear") || prompt.equalsIgnoreCase("reset")) {
            ConversationHistory.getInstance().clear();
            logDirect("LLM conversation history cleared.");
            return;
        }

        logDirect("Thinking...");
        ConversationHistory.getInstance().addUserMessage(prompt);
        AutonomousClient.executeTurn();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Interact with the LLM Autonomous Mode";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Sends a natural language prompt to the local LLM to execute.",
                "",
                "Usage:",
                "> bot <prompt...>"
        );
    }
}
