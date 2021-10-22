package me.egg82.antivpn.utils;

import com.google.common.reflect.TypeToken;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
import ninja.egg82.service.ServiceLocator;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public class ConfigurationFileUtil {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationFileUtil.class);

    private ConfigurationFileUtil() {}

    public static void reloadConfig(Object plugin, ProxyServer proxy, PluginDescription description, StorageHandler storageHandler, MessagingHandler messagingHandler) {
        ConfigurationNode config;
        try {
            config = getConfig(plugin, "config.yml", new File(new File(description.getSource().get().getParent().toFile(), description.getName().get()), "config.yml"));
        } catch (IOException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        boolean debug = config.node("debug").getBoolean(false);

        if (!debug) {
            Reflections.log = null;
        }

        if (debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Debug ").color(TextColor.YELLOW)).append(TextComponent.of("enabled").color(TextColor.WHITE)).build());
        }

        Locale language = getLanguage(config.node("lang").getString("en"));
        if (language == null) {
            logger.warn("lang is not a valid language. Using default value.");
            language = Locale.US;
        }
        if (debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Default language: ").color(TextColor.YELLOW)).append(TextComponent.of(language.getCountry() == null || language.getCountry().isEmpty() ? language.getLanguage() : language.getLanguage() + "-" + language.getCountry()).color(TextColor.WHITE)).build());
        }

        UUID serverID = ServerIDUtil.getID(new File(new File(description.getSource().get().getParent().toFile(), description.getName().get()), "stats-id.txt"));

        List<Storage> storage;
        try {
            storage = getStorage(proxy, description, config.node("storage", "engines"), new PoolSettings(config.node("storage", "settings")), debug, config.node("storage", "order").getList(TypeToken.of(String.class)), storageHandler);
        } catch (SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            storage = new ArrayList<>();
        }

        if (storage.isEmpty()) {
            throw new IllegalStateException("No storage has been defined in the config.yml");
        }

        if (debug) {
            for (Storage s : storage) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Added storage: ").color(TextColor.YELLOW)).append(TextComponent.of(s.getClass().getSimpleName()).color(TextColor.WHITE)).build());
            }
        }

        List<Messaging> messaging;
        try {
            messaging = getMessaging(proxy, config.node("messaging", "engines"), new PoolSettings(config.node("messaging", "settings")), debug, serverID, config.node("messaging", "order").getList(TypeToken.of(String.class)), messagingHandler);
        } catch (SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            messaging = new ArrayList<>();
        }

        if (debug) {
            for (Messaging m : messaging) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Added messaging: ").color(TextColor.YELLOW)).append(TextComponent.of(m.getClass().getSimpleName()).color(TextColor.WHITE)).build());
            }
        }

        Map<String, SourceAPI> sources = getAllSources(debug);
        Set<String> stringSources;
        try {
            stringSources = new LinkedHashSet<>(config.node("sources", "order").getList(TypeToken.of(String.class)));
        } catch (SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            stringSources = new LinkedHashSet<>();
        }

        for (Iterator<String> i = stringSources.iterator(); i.hasNext();) {
            String source = i.next();
            if (!config.node("sources", source, "enabled").getBoolean()) {
                if (debug) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of(source + " is disabled. Removing.").color(TextColor.DARK_RED)).build());
                }
                i.remove();
                continue;
            }

            Optional<SourceAPI> api = getAPI(source, sources);
            if (api.isPresent() && api.get().isKeyRequired() && config.node("sources", source, "key").getString("").isEmpty()) {
                if (debug) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of(source + " requires a key which was not provided. Removing.").color(TextColor.DARK_RED)).build());
                }
                i.remove();
            }
        }
        for(Iterator<Map.Entry<String, SourceAPI>> i = sources.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<String, SourceAPI> kvp = i.next();
            if (!stringSources.contains(kvp.getKey())) {
                if (debug) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Removed undefined source: ").color(TextColor.DARK_RED)).append(TextComponent.of(kvp.getKey()).color(TextColor.WHITE)).build());
                }
                i.remove();
            }
        }

        if (debug) {
            for (String source : stringSources) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Added source: ").color(TextColor.YELLOW)).append(TextComponent.of(source).color(TextColor.WHITE)).build());
            }
        }

        Optional<TimeUtil.Time> sourceCacheTime = TimeUtil.getTime(config.node("sources", "cache-time").getString("6hours"));
        if (!sourceCacheTime.isPresent()) {
            logger.warn("sources.cache-time is not a valid time pattern. Using default value.");
            sourceCacheTime = Optional.of(new TimeUtil.Time(6L, TimeUnit.HOURS));
        }

        if (debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Source cache time: ").color(TextColor.YELLOW)).append(TextComponent.of(sourceCacheTime.get().getMillis() + "ms").color(TextColor.WHITE)).build());
        }

        Set<String> ignoredIps;
        try {
            ignoredIps = new HashSet<>(config.node("action", "ignore").getList(TypeToken.of(String.class)));
        } catch (SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            ignoredIps = new HashSet<>();
        }
        for (Iterator<String> i = ignoredIps.iterator(); i.hasNext();) {
            String ip = i.next();
            if (!ValidationUtil.isValidIp(ip) && !ValidationUtil.isValidIPRange(ip)) {
                if (debug) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Removed invalid ignore IP/range: ").color(TextColor.DARK_RED)).append(TextComponent.of(ip).color(TextColor.WHITE)).build());
                }
                i.remove();
            }
        }

        if (debug) {
            for (String ip : ignoredIps) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Adding ignored IP or range: ").color(TextColor.YELLOW)).append(TextComponent.of(ip).color(TextColor.WHITE)).build());
            }
        }

        Optional<TimeUtil.Time> cacheTime = TimeUtil.getTime(config.node("connection", "cache-time").getString("1minute"));
        if (!cacheTime.isPresent()) {
            logger.warn("connection.cache-time is not a valid time pattern. Using default value.");
            cacheTime = Optional.of(new TimeUtil.Time(1L, TimeUnit.MINUTES));
        }

        if (debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Memory cache time: ").color(TextColor.YELLOW)).append(TextComponent.of(cacheTime.get().getMillis() + "ms").color(TextColor.WHITE)).build());
        }

        List<String> vpnActionCommands;
        try {
            vpnActionCommands = new ArrayList<>(config.node("action", "vpn", "commands").getList(String.class));
        } catch (SerializationException ex) {
            logger.error(ex.getMessage(), ex);
            vpnActionCommands = new ArrayList<>();
        }
        vpnActionCommands.removeIf(action -> action == null || action.isEmpty());

        if (debug) {
            for (String action : vpnActionCommands) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Including command action for VPN usage: ").color(TextColor.YELLOW)).append(TextComponent.of(action).color(TextColor.WHITE)).build());
            }
        }
        VPNAlgorithmMethod vpnAlgorithmMethod = VPNAlgorithmMethod.getByName(config.node("action", "vpn", "algorithm", "method").getString("cascade"));
        if (vpnAlgorithmMethod == null) {
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

        if (debug) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("API threads: ").color(TextColor.YELLOW)).append(TextComponent.of(cachedValues.getThreads()).color(TextColor.WHITE)).build());
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("API timeout: ").color(TextColor.YELLOW)).append(TextComponent.of(cachedValues.getTimeout() + "ms").color(TextColor.WHITE)).build());
        }
    }

    public static ConfigurationNode getConfig(Object plugin, String resourcePath, File fileOnDisk) throws IOException {
        File parentDir = fileOnDisk.getParentFile();
        if (parentDir.exists() && !parentDir.isDirectory()) {
            Files.delete(parentDir.toPath());
        }
        if (!parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("Could not create parent directory structure.");
            }
        }
        if (fileOnDisk.exists() && fileOnDisk.isDirectory()) {
            Files.delete(fileOnDisk.toPath());
        }

        if (!fileOnDisk.exists()) {
            try (InputStreamReader reader = new InputStreamReader(plugin.getClass().getClassLoader().getResourceAsStream(resourcePath));
                 BufferedReader in = new BufferedReader(reader);
                 FileWriter writer = new FileWriter(fileOnDisk);
                 BufferedWriter out = new BufferedWriter(writer)) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.write(line + System.lineSeparator());
                }
            }
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK).indent(2).file(fileOnDisk).build();
        ConfigurationNode root = loader.load(ConfigurationOptions.defaults().header("Comments are gone because update :(. Click here for new config + comments: https://forums.velocitypowered.com/t/anti-vpn-get-the-best-save-money-on-overpriced-plugins-and-block-vpn-users/207"));
        ConfigurationVersionUtil.conformVersion(loader, root, fileOnDisk);
        return root;
    }

    private static Locale getLanguage(String lang) {
        for (Locale locale : Locale.getAvailableLocales()) {
            if (locale.getLanguage().equalsIgnoreCase(lang)) {
                return locale;
            }

            String l = locale.getCountry() == null || locale.getCountry().isEmpty() ? locale.getLanguage() : locale.getLanguage() + "-" + locale.getCountry();
            if (l.equalsIgnoreCase(lang)) {
                return locale;
            }
        }
        return null;
    }

    private static List<Storage> getStorage(ProxyServer proxy, PluginDescription description, ConfigurationNode enginesNode, PoolSettings settings, boolean debug, List<String> names, StorageHandler handler) {
        List<Storage> retVal = new ArrayList<>();

        for (String name : names) {
            name = name.toLowerCase();
            switch (name) {
                case "mysql": {
                    if (!enginesNode.node(name, "enabled").getBoolean()) {
                        if (debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of(name + " is disabled. Removing.").color(TextColor.DARK_RED)).build());
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.node(name, "connection");
                    String options = connectionNode.node("options").getString("useSSL=false&useUnicode=true&characterEncoding=utf8");
                    if (options.length() > 0 && options.charAt(0) == '?') {
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
                                        .build()
                        );
                    } catch (IOException | StorageException ex) {
                        logger.error("Could not create MySQL instance.", ex);
                    }
                    break;
                }
                case "redis": {
                    if (!enginesNode.node(name, "enabled").getBoolean()) {
                        if (debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of(name + " is disabled. Removing.").color(TextColor.DARK_RED)).build());
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
                                        .build()
                        );
                    } catch (StorageException ex) {
                        logger.error("Could not create Redis instance.", ex);
                    }
                    break;
                }
                case "sqlite": {
                    if (!enginesNode.node(name, "enabled").getBoolean()) {
                        if (debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of(name + " is disabled. Removing.").color(TextColor.DARK_RED)).build());
                        }
                        continue;
                    }
                    ConfigurationNode connectionNode = enginesNode.node(name, "connection");
                    String options = connectionNode.node("options").getString("useUnicode=true&characterEncoding=utf8");
                    if (options.length() > 0 && options.charAt(0) == '?') {
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
                                        .build()
                        );
                    } catch (IOException | StorageException ex) {
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

        for (String name : names) {
            name = name.toLowerCase();
            switch (name) {
                case "rabbitmq": {
                    if (!enginesNode.node(name, "enabled").getBoolean()) {
                        if (debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of(name + " is disabled. Removing.").color(TextColor.DARK_RED)).build());
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
                                        .build()
                        );
                    } catch (MessagingException ex) {
                        logger.error("Could not create RabbitMQ instance.", ex);
                    }
                    break;
                }
                case "redis": {
                    if (!enginesNode.node(name, "enabled").getBoolean()) {
                        if (debug) {
                            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of(name + " is disabled. Removing.").color(TextColor.DARK_RED)).build());
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
                                        .build()
                        );
                    } catch (MessagingException ex) {
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

    private static Optional<SourceAPI> getAPI(String name, Map<String, SourceAPI> sources) { return Optional.ofNullable(sources.getOrDefault(name, null)); }

    private static Map<String, SourceAPI> getAllSources(boolean debug) {
        List<Class<SourceAPI>> sourceClasses = PackageFilter.getClasses(SourceAPI.class, "me.egg82.antivpn.apis.vpn", false, false, false);
        Map<String, SourceAPI> retVal = new HashMap<>();
        for (Class<SourceAPI> clazz : sourceClasses) {
            if (debug) {
                logger.info("Initializing VPN API " + clazz.getName());
            }

            try {
                SourceAPI api = clazz.newInstance();
                retVal.put(api.getName(), api);
            } catch (InstantiationException | IllegalAccessException ex) {
                logger.error(ex.getMessage(), ex);
            }
        }
        return retVal;
    }

    private static class AddressPort {
        private String address;
        private int port;

        public AddressPort(String node, String raw, int defaultPort) {
            String address = raw;
            int portIndex = address.indexOf(':');
            int port;
            if (portIndex > -1) {
                port = Integer.parseInt(address.substring(portIndex + 1));
                address = address.substring(0, portIndex);
            } else {
                logger.warn(node + " port is an unknown value. Using default value.");
                port = defaultPort;
            }

            this.address = address;
            this.port = port;
        }

        public String getAddress() { return address; }

        public int getPort() { return port; }
    }

    private static class PoolSettings {
        private int minPoolSize;
        private int maxPoolSize;
        private long maxLifetime;
        private long timeout;

        public PoolSettings(ConfigurationNode settingsNode) {
            minPoolSize = settingsNode.node("min-idle").getInt();
            maxPoolSize = settingsNode.node("max-pool-size").getInt();
            maxLifetime = settingsNode.node("max-lifetime").getLong();
            timeout = settingsNode.node("timeout").getLong();
        }

        public int getMinPoolSize() { return minPoolSize; }

        public int getMaxPoolSize() { return maxPoolSize; }

        public long getMaxLifetime() { return maxLifetime; }

        public long getTimeout() { return timeout; }
    }
}
