package baritone.api.plugins;

import baritone.api.IBaritone;

/**
 * A plugin for Baritone.
 */
public interface IBaritonePlugin {

    /**
     * Called when the plugin is initialized.
     *
     * @param baritone The Baritone instance
     */
    void onInitialize(IBaritone baritone);

    /**
     * @return The name of the plugin
     */
    String getName();

    /**
     * @return The version of the plugin
     */
    String getVersion();
}
