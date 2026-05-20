package baritone.plugins;

import baritone.api.IBaritone;
import baritone.api.plugins.IBaritonePlugin;
import baritone.api.plugins.IPluginManager;
import baritone.api.plugins.hooks.IDistantChunkProvider;
import baritone.api.plugins.hooks.IMinimapHook;
import baritone.Baritone;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PluginManager implements IPluginManager {

    private final Baritone baritone;
    private final List<IBaritonePlugin> plugins = new ArrayList<>();
    private final MinimapHook minimapHook;
    private IDistantChunkProvider distantChunkProvider;

    public PluginManager(Baritone baritone) {
        this.baritone = baritone;
        this.minimapHook = new MinimapHook(baritone);
    }

    @Override
    public List<IBaritonePlugin> getPlugins() {
        return plugins;
    }

    @Override
    public void registerPlugin(IBaritonePlugin plugin) {
        plugins.add(plugin);
        plugin.onInitialize(baritone);
    }

    @Override
    public IMinimapHook getMinimapHook() {
        return minimapHook;
    }

    @Override
    public void setDistantChunkProvider(IDistantChunkProvider provider) {
        this.distantChunkProvider = provider;
    }

    @Override
    public IDistantChunkProvider getDistantChunkProvider() {
        return distantChunkProvider;
    }

    private static class MinimapHook implements IMinimapHook {
        private final Baritone baritone;

        public MinimapHook(Baritone baritone) {
            this.baritone = baritone;
        }

        @Override
        public Optional<List<BlockPos>> getCurrentPath() {
            // Implementation detail: get path from pathingBehavior
            return Optional.ofNullable(baritone.getPathingBehavior().getCurrentPath())
                    .map(path -> path.positions());
        }

        @Override
        public Optional<BlockPos> getCurrentGoal() {
            return Optional.ofNullable(baritone.getCustomGoalProcess().getGoal())
                    .filter(goal -> goal instanceof net.minecraft.core.BlockPos)
                    .map(goal -> (BlockPos) goal);
        }

        @Override
        public void setGoal(BlockPos pos) {
            baritone.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(pos));
        }

        @Override
        public void clearGoal() {
            baritone.getPathingBehavior().cancelEverything();
        }
    }
}
