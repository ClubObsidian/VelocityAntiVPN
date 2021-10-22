package me.egg82.antivpn.utils;

import java.util.Optional;
import me.egg82.antivpn.extended.CachedConfigValues;
import org.spongepowered.configurate.ConfigurationNode;

public class ConfigUtil {

    private static ConfigurationNode config = null;
    private static CachedConfigValues cachedConfig = null;

    private ConfigUtil() {}

    public static void setConfiguration(ConfigurationNode config, CachedConfigValues cachedConfig) {
        ConfigUtil.config = config;
        ConfigUtil.cachedConfig = cachedConfig;
    }

    /**
     * Grabs the config instance from ServiceLocator
     * @return Optional, instance of the Configuration class
     */
    public static Optional<ConfigurationNode> getConfig() {
        return Optional.ofNullable(config);
    }

    /**
     * Grabs the cached config instance from ServiceLocator
     * @return Optional, instance of the CachedConfigValues class
     */
    public static Optional<CachedConfigValues> getCachedConfig() {
        return Optional.ofNullable(cachedConfig);
    }

    public static boolean getDebugOrFalse() {
        Optional<CachedConfigValues> cachedConfig = getCachedConfig();
        return cachedConfig.isPresent() && cachedConfig.get().getDebug();
    }
}
