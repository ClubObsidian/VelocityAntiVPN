package me.egg82.antivpn.apis.vpn;

import me.egg82.antivpn.APIException;
import me.egg82.antivpn.apis.SourceAPI;
import me.egg82.antivpn.extended.CachedConfigValues;
import me.egg82.antivpn.utils.ConfigUtil;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.Optional;

public abstract class AbstractSourceAPI implements SourceAPI {
    protected final ConfigurationNode getSourceConfigNode() throws APIException {
        Optional<ConfigurationNode> config = ConfigUtil.getConfig();
        if(!config.isPresent()) {
            throw new APIException(true, "Could not get configuration.");
        }

        return config.get().node("sources", getName());
    }

    protected final CachedConfigValues getCachedConfig() throws APIException {
        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if(!cachedConfig.isPresent()) {
            throw new APIException(true, "Cached config could not be fetched.");
        }

        return cachedConfig.get();
    }
}
