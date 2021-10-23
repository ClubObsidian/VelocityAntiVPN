package me.egg82.antivpn.utils;

import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.ProxyServer;
import me.egg82.antivpn.VPNAPI;
import me.egg82.antivpn.apis.SourceAPI;
import me.egg82.antivpn.enums.VPNAlgorithmMethod;
import me.egg82.antivpn.extended.CachedConfigValues;
import me.egg82.antivpn.messaging.Messaging;
import me.egg82.antivpn.messaging.MessagingException;
import me.egg82.antivpn.messaging.RabbitMQ;
import me.egg82.antivpn.services.MessagingHandler;
import me.egg82.antivpn.services.StorageHandler;
import me.egg82.antivpn.storage.MySQL;
import me.egg82.antivpn.storage.SQLite;
import me.egg82.antivpn.storage.Storage;
import me.egg82.antivpn.storage.StorageException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import ninja.egg82.reflect.PackageFilter;
import ninja.egg82.service.ServiceLocator;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ConfigurationFileUtil {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationFileUtil.class);

    private ConfigurationFileUtil() {
    }

    public static void reloadConfig(Object plugin, ProxyServer proxy, PluginDescription description, StorageHandler storageHandler, MessagingHandler messagingHandler) {
        ConfigurationNode config;
        try {
            config = getConfig(plugin, "config.yml", new File(new File(description.getSource().get().getParent().toFile(), description.getName().get()), "config.yml"));
        } catch(IOException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        boolean debug = config.node("debug").getBoolean(false);

        if(!debug) {
            Reflections.log = null;
        }

        if(debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Debug ").color(NamedTextColor.YELLOW)).append(Component.text("enabled").color(NamedTextColor.WHITE)));
        }

        Locale language = getLanguage(config.node("lang").getString("en"));
        if(language == null) {
            logger.warn("lang is not a valid language. Using default value.");
            language = Locale.US;
        }
        if(debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Default language: ").color(NamedTextColor.YELLOW)).append(Component.text(language.getCountry() == null || language.getCountry().isEmpty() ? language.getLanguage() : language.getLanguage() + "-" + language.getCountry()).color(NamedTextColor.WHITE)));
        }

        UUID serverID = ServerIDUtil.getID(new File(new File(description.getSource().get().getParent().toFile(), description.getName().get()), "stats-id.txt"));

        List<Storage> storage;
        try {
            storage = getStorage(proxy, description, config.node("storage", "engines"), new PoolSettings(config.node("storage", "settings")), debug, config.node("storage", "order").getList(String.class), storageHandler);
        } catch(SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            storage = new ArrayList<>();
        }

        if(storage.isEmpty()) {
            throw new IllegalStateException("No storage has been defined in the config.yml");
        }

        if(debug) {
            for(Storage s : storage) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Added storage: ").color(NamedTextColor.YELLOW)).append(Component.text(s.getClass().getSimpleName()).color(NamedTextColor.WHITE)));
            }
        }

        List<Messaging> messaging;
        try {
            messaging = getMessaging(proxy, config.node("messaging", "engines"), new PoolSettings(config.node("messaging", "settings")), debug, serverID, config.node("messaging", "order").getList(String.class), messagingHandler);
        } catch(SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            messaging = new ArrayList<>();
        }

        if(debug) {
            for(Messaging m : messaging) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Added messaging: ").color(NamedTextColor.YELLOW)).append(Component.text(m.getClass().getSimpleName()).color(NamedTextColor.WHITE)));
            }
        }

        Map<String, SourceAPI> sources = getAllSources(debug);
        Set<String> stringSources;
        try {
            stringSources = new LinkedHashSet<>(config.node("sources", "order").getList(String.class));
        } catch(SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            stringSources = new LinkedHashSet<>();
        }

        for(Iterator<String> i = stringSources.iterator(); i.hasNext(); ) {
            String source = i.next();
            if(!config.node("sources", source, "enabled").getBoolean()) {
                if(debug) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(source + " is disabled. Removing.").color(NamedTextColor.DARK_RED)));
                }
                i.remove();
                continue;
            }

            Optional<SourceAPI> api = getAPI(source, sources);
            if(api.isPresent() && api.get().isKeyRequired() && config.node("sources", source, "key").getString("").isEmpty()) {
                if(debug) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(source + " requires a key which was not provided. Removing.").color(NamedTextColor.DARK_RED)));
                }
                i.remove();
            }
        }
        for(Iterator<Map.Entry<String, SourceAPI>> i = sources.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<String, SourceAPI> kvp = i.next();
            if(!stringSources.contains(kvp.getKey())) {
                if(debug) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Removed undefined source: ").color(NamedTextColor.DARK_RED)).append(Component.text(kvp.getKey()).color(NamedTextColor.WHITE)));
                }
                i.remove();
            }
        }

        if(debug) {
            for(String source : stringSources) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Added source: ").color(NamedTextColor.YELLOW)).append(Component.text(source).color(NamedTextColor.WHITE)));
            }
        }

        Optional<TimeUtil.Time> sourceCacheTime = TimeUtil.getTime(config.node("sources", "cache-time").getString("6hours"));
        if(!sourceCacheTime.isPresent()) {
            logger.warn("sources.cache-time is not a valid time pattern. Using default value.");
            sourceCacheTime = Optional.of(new TimeUtil.Time(6L, TimeUnit.HOURS));
        }

        if(debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Source cache time: ").color(NamedTextColor.YELLOW)).append(Component.text(sourceCacheTime.get().getMillis() + "ms").color(NamedTextColor.WHITE)));
        }

        Set<String> ignoredIps;
        try {
            ignoredIps = new HashSet<>(config.node("action", "ignore").getList(String.class));
        } catch(SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            ignoredIps = new HashSet<>();
        }
        for(Iterator<String> i = ignoredIps.iterator(); i.hasNext(); ) {
            String ip = i.next();
            if(!ValidationUtil.isValidIp(ip) && !ValidationUtil.isValidIPRange(ip)) {
                if(debug) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Removed invalid ignore IP/range: ").color(NamedTextColor.DARK_RED)).append(Component.text(ip).color(NamedTextColor.WHITE)));
                }
                i.remove();
            }
        }

        if(debug) {
            for(String ip : ignoredIps) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Adding ignored IP or range: ").color(NamedTextColor.YELLOW)).append(Component.text(ip).color(NamedTextColor.WHITE)));
            }
        }

        Optional<TimeUtil.Time> cacheTime = TimeUtil.getTime(config.node("connection", "cache-time").getString("1minute"));
        if(!cacheTime.isPresent()) {
            logger.warn("connection.cache-time is not a valid time pattern. Using default value.");
            cacheTime = Optional.of(new TimeUtil.Time(1L, TimeUnit.MINUTES));
        }

        if(debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Memory cache time: ").color(NamedTextColor.YELLOW)).append(Component.text(cacheTime.get().getMillis() + "ms").color(NamedTextColor.WHITE)));
        }

        List<String> vpnActionCommands;
        try {
            vpnActionCommands = new ArrayList<>(config.node("action", "vpn", "commands").getList(String.class));
        } catch(SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            vpnActionCommands = new ArrayList<>();
        }
        vpnActionCommands.removeIf(action -> action == null || action.isEmpty());

        if(debug) {
            for(String action : vpnActionCommands) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("Including command action for VPN usage: ").color(NamedTextColor.YELLOW)).append(Component.text(action).color(NamedTextColor.WHITE)));
            }
        }
        VPNAlgorithmMethod vpnAlgorithmMethod = VPNAlgorithmMethod.getByName(config.node("action", "vpn", "algorithm", "method").getString("cascade"));
        if(vpnAlgorithmMethod == null) {
            logger.warn("action.vpn.algorithm.method is not a valid type. Using default value.");
            vpnAlgorithmMethod = VPNAlgorithmMethod.CASCADE;
        }

        double vpnAlgorithmConsensus = config.node("action", "vpn", "algorithm", "min-consensus").getDouble(0.6d);
        vpnAlgorithmConsensus = Math.max(0.0d, Math.min(1.0d, vpnAlgorithmConsensus));

        CachedConfigValues cachedValues = CachedConfigValues.builder()
                .debug(debug)
                .storage(storage)
                .messaging(messaging)
                .sources(sources)
                .sourceCacheTime(sourceCacheTime.get())
                .ignoredIps(ignoredIps)
                .cacheTime(cacheTime.get())
                .threads(config.node("connection", "threads").getInt(4))
                .timeout(config.node("connection", "timeout").getLong(5000L))
                .vpnKickMessage(config.node("action", "vpn", "kick-message").getString("&cPlease disconnect from your proxy or VPN before re-joining!"))
                .vpnActionCommands(vpnActionCommands)
                .vpnAlgorithmMethod(vpnAlgorithmMethod)
                .vpnAlgorithmConsensus(vpnAlgorithmConsensus)
                .build();

        ConfigUtil.setConfiguration(config, cachedValues);

        ServiceLocator.register(config);
        ServiceLocator.register(cachedValues);

        VPNAPI.reload();

        if(debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("API threads: ").color(NamedTextColor.YELLOW)).append(Component.text(cachedValues.getThreads()).color(NamedTextColor.WHITE)));
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("API timeout: ").color(NamedTextColor.YELLOW)).append(Component.text(cachedValues.getTimeout() + "ms").color(NamedTextColor.WHITE)));
        }
    }

    public static ConfigurationNode getConfig(Object plugin, String resourcePath, File fileOnDisk) throws IOException {
        File parentDir = fileOnDisk.getParentFile();
        if(parentDir.exists() && !parentDir.isDirectory()) {
            Files.delete(parentDir.toPath());
        }
        if(!parentDir.exists()) {
            if(!parentDir.mkdirs()) {
                throw new IOException("Could not create parent directory structure.");
            }
        }
        if(fileOnDisk.exists() && fileOnDisk.isDirectory()) {
            Files.delete(fileOnDisk.toPath());
        }

        if(!fileOnDisk.exists()) {
            try(InputStreamReader reader = new InputStreamReader(plugin.getClass().getClassLoader().getResourceAsStream(resourcePath));
                BufferedReader in = new BufferedReader(reader);
                FileWriter writer = new FileWriter(fileOnDisk);
                BufferedWriter out = new BufferedWriter(writer)) {
                String line;
                while((line = in.readLine()) != null) {
                    out.write(line + System.lineSeparator());
                }
            }
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .file(fileOnDisk)
                .build();
        ConfigurationNode root = loader.load(ConfigurationOptions.defaults().header("Comments are gone because update :(. Click here for new config + comments: https://forums.velocitypowered.com/t/anti-vpn-get-the-best-save-money-on-overpriced-plugins-and-block-vpn-users/207"));
        ConfigurationVersionUtil.conformVersion(loader, root, fileOnDisk);
        return root;
    }

    private static Locale getLanguage(String lang) {
        for(Locale locale : Locale.getAvailableLocales()) {
            if(locale.getLanguage().equalsIgnoreCase(lang)) {
                return locale;
            }

            String l = locale.getCountry() == null || locale.getCountry().isEmpty() ? locale.getLanguage() : locale.getLanguage() + "-" + locale.getCountry();
            if(l.equalsIgnoreCase(lang)) {
                return locale;
            }
        }
        return null;
    }

    private static List<Storage> getStorage(ProxyServer proxy, PluginDescription description, ConfigurationNode enginesNode, PoolSettings settings, boolean debug, List<String> names, StorageHandler handler) {
        List<Storage> retVal = new ArrayList<>();

        for(String name : names) {
            name = name.toLowerCase();
            switch(name) {
                case "mysql": {
                    if(!enginesNode.node(name, "enabled").getBoolean()) {
                        if(debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(name + " is disabled. Removing.").color(NamedTextColor.DARK_RED)));
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.node(name, "connection");
                    String options = connectionNode.node("options").getString("useSSL=false&useUnicode=true&characterEncoding=utf8");
                    if(options.length() > 0 && options.charAt(0) == '?') {
                        options = options.substring(1);
                    }
                    AddressPort url = new AddressPort("storage.engines." + name + ".connection.address", connectionNode.node("address").getString("127.0.0.1:3306"), 3306);
                    try {
                        retVal.add(
                                MySQL.builder(handler)
                                        .url(url.address, url.port, connectionNode.node("database").getString("anti_vpn"), connectionNode.node("prefix").getString("avpn_"))
                                        .credentials(connectionNode.node("username").getString(""), connectionNode.node("password").getString(""))
                                        .options(options)
                                        .poolSize(settings.minPoolSize, settings.maxPoolSize)
                                        .life(settings.maxLifetime, settings.timeout)
                                        .build());
                    } catch(IOException | StorageException ex) {
                        logger.error("Could not create MySQL instance.", ex);
                    }
                    break;
                }
                case "redis": {
                    if(!enginesNode.node(name, "enabled").getBoolean()) {
                        if(debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(name + " is disabled. Removing.").color(NamedTextColor.DARK_RED)));
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.node(name, "connection");
                    AddressPort url = new AddressPort("storage.engines." + name + ".connection.address", connectionNode.node("address").getString("127.0.0.1:6379"), 6379);
                    try {
                        retVal.add(
                                me.egg82.antivpn.storage.Redis.builder(handler)
                                        .url(url.address, url.port, connectionNode.node("prefix").getString("avpn_"))
                                        .credentials(connectionNode.node("password").getString(""))
                                        .poolSize(settings.minPoolSize, settings.maxPoolSize)
                                        .life(settings.maxLifetime, (int) settings.timeout)
                                        .build());
                    } catch(StorageException ex) {
                        logger.error("Could not create Redis instance.", ex);
                    }
                    break;
                }
                case "sqlite": {
                    if(!enginesNode.node(name, "enabled").getBoolean()) {
                        if(debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(name + " is disabled. Removing.").color(NamedTextColor.DARK_RED)));
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.node(name, "connection");
                    String options = connectionNode.node("options").getString("useUnicode=true&characterEncoding=utf8");
                    if(options.length() > 0 && options.charAt(0) == '?') {
                        options = options.substring(1);
                    }
                    String file = connectionNode.node("file").getString("anti_vpn.db");
                    try {
                        retVal.add(
                                SQLite.builder(handler)
                                        .file(new File(new File(description.getSource().get().getParent().toFile(), description.getName().get()), file), connectionNode.node("prefix").getString("avpn_"))
                                        .options(options)
                                        .poolSize(settings.minPoolSize, settings.maxPoolSize)
                                        .life(settings.maxLifetime, settings.timeout)
                                        .build());
                    } catch(IOException | StorageException ex) {
                        logger.error("Could not create SQLite instance.", ex);
                    }
                    break;
                }
                default: {
                    logger.warn("Unknown storage type: \"" + name + "\"");
                    break;
                }
            }
        }

        return retVal;
    }

    private static List<Messaging> getMessaging(ProxyServer proxy, ConfigurationNode enginesNode, PoolSettings settings, boolean debug, UUID serverID, List<String> names, MessagingHandler handler) {
        List<Messaging> retVal = new ArrayList<>();

        for(String name : names) {
            name = name.toLowerCase();
            switch(name) {
                case "rabbitmq": {
                    if(!enginesNode.node(name, "enabled").getBoolean()) {
                        if(debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(name + " is disabled. Removing.").color(NamedTextColor.DARK_RED)));
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.node(name, "connection");
                    AddressPort url = new AddressPort("messaging.engines." + name + ".connection.address", connectionNode.node("address").getString("127.0.0.1:5672"), 5672);
                    try {
                        retVal.add(
                                RabbitMQ.builder(serverID, handler)
                                        .url(url.address, url.port, connectionNode.node("v-host").getString("/"))
                                        .credentials(connectionNode.node("username").getString("guest"), connectionNode.node("password").getString("guest"))
                                        .timeout((int) settings.timeout)
                                        .build());
                    } catch(MessagingException ex) {
                        logger.error("Could not create RabbitMQ instance.", ex);
                    }
                    break;
                }
                case "redis": {
                    if(!enginesNode.node(name, "enabled").getBoolean()) {
                        if(debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(name + " is disabled. Removing.").color(NamedTextColor.DARK_RED)));
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.node(name, "connection");
                    AddressPort url = new AddressPort("messaging.engines." + name + ".connection.address", connectionNode.node("address").getString("127.0.0.1:6379"), 6379);
                    try {
                        retVal.add(
                                me.egg82.antivpn.messaging.Redis.builder(serverID, handler)
                                        .url(url.address, url.port)
                                        .credentials(connectionNode.node("password").getString(""))
                                        .poolSize(settings.minPoolSize, settings.maxPoolSize)
                                        .life(settings.maxLifetime, (int) settings.timeout)
                                        .build());
                    } catch(MessagingException ex) {
                        logger.error("Could not create Redis instance.", ex);
                    }
                    break;
                }
                default: {
                    logger.warn("Unknown messaging type: \"" + name + "\"");
                    break;
                }
            }
        }

        return retVal;
    }

    private static Optional<SourceAPI> getAPI(String name, Map<String, SourceAPI> sources) {
        return Optional.ofNullable(sources.getOrDefault(name, null));
    }

    private static Map<String, SourceAPI> getAllSources(boolean debug) {
        List<Class<SourceAPI>> sourceClasses = PackageFilter.getClasses(SourceAPI.class, "me.egg82.antivpn.apis.vpn", false, false, false);
        Map<String, SourceAPI> retVal = new HashMap<>();
        for(Class<SourceAPI> clazz : sourceClasses) {
            if(debug) {
                logger.info("Initializing VPN API " + clazz.getName());
            }

            try {
                SourceAPI api = clazz.newInstance();
                retVal.put(api.getName(), api);
            } catch(InstantiationException | IllegalAccessException ex) {
                logger.error(ex.getMessage(), ex);
            }
        }
        return retVal;
    }

    private static class AddressPort {
        private final String address;
        private final int port;

        public AddressPort(String node, String raw, int defaultPort) {
            String address = raw;
            int portIndex = address.indexOf(':');
            int port;
            if(portIndex > -1) {
                port = Integer.parseInt(address.substring(portIndex + 1));
                address = address.substring(0, portIndex);
            } else {
                logger.warn(node + " port is an unknown value. Using default value.");
                port = defaultPort;
            }

            this.address = address;
            this.port = port;
        }

        public String getAddress() {
            return address;
        }

        public int getPort() {
            return port;
        }
    }

    private static class PoolSettings {
        private final int minPoolSize;
        private final int maxPoolSize;
        private final long maxLifetime;
        private final long timeout;

        public PoolSettings(ConfigurationNode settingsNode) {
            minPoolSize = settingsNode.node("min-idle").getInt();
            maxPoolSize = settingsNode.node("max-pool-size").getInt();
            maxLifetime = settingsNode.node("max-lifetime").getLong();
            timeout = settingsNode.node("timeout").getLong();
        }

        public int getMinPoolSize() {
            return minPoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public long getMaxLifetime() {
            return maxLifetime;
        }

        public long getTimeout() {
            return timeout;
        }
    }
}
