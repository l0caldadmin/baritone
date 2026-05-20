package baritone.api.plugins;

import baritone.api.plugins.hooks.IMinimapHook;
import baritone.api.plugins.hooks.IDistantChunkProvider;
import java.util.List;

/**
 * Manages Baritone plugins and external hooks.
 */
public interface IPluginManager {

    /**
     * @return All registered plugins.
     */
    List<IBaritonePlugin> getPlugins();

    /**
     * Register a new plugin.
     *
     * @param plugin The plugin to register.
     */
    void registerPlugin(IBaritonePlugin plugin);

    /**
     * @return The minimap hook instance.
     */
    IMinimapHook getMinimapHook();

    /**
     * Set the distant chunk provider.
     *
     * @param provider The provider.
     */
    void setDistantChunkProvider(IDistantChunkProvider provider);

    /**
     * @return The current distant chunk provider, if any.
     */
    IDistantChunkProvider getDistantChunkProvider();
}
