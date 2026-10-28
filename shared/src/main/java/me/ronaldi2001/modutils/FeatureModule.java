package me.ronaldi2001.modutils;

/**
 * One feature of the plugin (mod compat, mod block, ...). Each lives in its own section of config.yml, named by
 * {@link #getConfigKey()}, and is started and stopped by {@link ModUtilsPlugin}.
 */
public interface FeatureModule {
	/** Name of the feature's section in config.yml. */
	String getConfigKey();

	/** Whether the feature is running now. */
	boolean isActive();

	/**
	 * Applies the config as it is now.
	 *
	 * @return whether the feature is running now
	 */
	boolean start();

	/** Stops the feature and cleans up after it. */
	void stop();

	/**
	 * Applies a changed setting at {@code path} (a full config path inside this module's section).
	 *
	 * @return whether the feature is running now
	 */
	default boolean restart(String path) {
		this.stop();
		return this.start();
	}
}
