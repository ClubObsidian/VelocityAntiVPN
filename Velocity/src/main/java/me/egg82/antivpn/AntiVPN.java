package me.egg82.antivpn;

import co.aikar.commands.CommandIssuer;
import co.aikar.commands.ConditionFailedException;
import co.aikar.commands.MessageType;
import co.aikar.commands.RegisteredCommand;
import co.aikar.commands.VelocityCommandManager;
import co.aikar.commands.VelocityLocales;
import co.aikar.locales.MessageKey;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import me.egg82.antivpn.apis.SourceAPI;
import me.egg82.antivpn.commands.AntiVPNCommand;
import me.egg82.antivpn.enums.Message;
import me.egg82.antivpn.events.PlayerEvents;
import me.egg82.antivpn.extended.CachedConfigValues;
import me.egg82.antivpn.hooks.PluginHook;
import me.egg82.antivpn.services.PluginMessageFormatter;
import me.egg82.antivpn.services.StorageMessagingHandler;
import me.egg82.antivpn.storage.Storage;
import me.egg82.antivpn.utils.ConfigUtil;
import me.egg82.antivpn.utils.ConfigurationFileUtil;
import me.egg82.antivpn.utils.LanguageFileUtil;
import me.egg82.antivpn.utils.ValidationUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import ninja.egg82.service.ServiceLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AntiVPN {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private VelocityCommandManager commandManager;

    private final List<ScheduledTask> tasks = new ArrayList<>();

    private final Object plugin;
    private final ProxyServer proxy;
    private final PluginDescription description;

    private CommandIssuer consoleCommandIssuer = null;

    public AntiVPN(Object plugin, ProxyServer proxy, PluginDescription description) {
        if(plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null.");
        }
        if(proxy == null) {
            throw new IllegalArgumentException("proxy cannot be null.");
        }
        if(description == null) {
            throw new IllegalArgumentException("description cannot be null.");
        }

        this.plugin = plugin;
        this.proxy = proxy;
        this.description = description;
    }

    public void onLoad() {
    }

    public void onEnable() {
        this.commandManager = new VelocityCommandManager(this.proxy, this.plugin);
        this.commandManager.enableUnstableAPI("help");

        this.consoleCommandIssuer = this.commandManager.getCommandIssuer(this.proxy.getConsoleCommandSource());

        loadServices();
        loadLanguages();
        loadCommands();
        loadEvents();
        loadTasks();
        loadHooks();

        this.consoleCommandIssuer.sendInfo(Message.GENERAL__ENABLED);
        this.consoleCommandIssuer.sendInfo(Message.GENERAL__LOAD,
                "{version}", this.description.getVersion().get(),
                "{commands}", String.valueOf(this.commandManager.getRegisteredRootCommands().size()),
                "{tasks}", String.valueOf(this.tasks.size())
        );
    }

    public void onDisable() {
        this.commandManager.unregisterCommands();

        for(ScheduledTask task : this.tasks) {
            task.cancel();
        }
        this.tasks.clear();

        this.unloadHooks();
        this.unloadServices();

        this.consoleCommandIssuer.sendInfo(Message.GENERAL__DISABLED);
    }

    private void loadLanguages() {
        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if(!cachedConfig.isPresent()) {
            throw new RuntimeException("Cached config could not be fetched.");
        }

        VelocityLocales locales = commandManager.getLocales();

        try {
            for(Locale locale : Locale.getAvailableLocales()) {
                Optional<File> localeFile = LanguageFileUtil.getLanguage(plugin, description, locale);
                if(localeFile.isPresent()) {
                    commandManager.addSupportedLanguage(locale);
                    loadYamlLanguageFile(locales, localeFile.get(), locale);
                }
            }
        } catch(IOException ex) {
            logger.error(ex.getMessage(), ex);
        }

        locales.loadLanguages();
        locales.setDefaultLocale(cachedConfig.get().getLanguage());
        commandManager.usePerIssuerLocale(true);

        this.commandManager.setFormat(MessageType.ERROR, new PluginMessageFormatter(commandManager, Message.GENERAL__HEADER));
        this.commandManager.setFormat(MessageType.INFO, new PluginMessageFormatter(commandManager, Message.GENERAL__HEADER));
        this.commandManager.setFormat(MessageType.ERROR, NamedTextColor.DARK_RED, NamedTextColor.YELLOW, NamedTextColor.AQUA,
                NamedTextColor.WHITE);
        this.commandManager.setFormat(MessageType.INFO, NamedTextColor.WHITE, NamedTextColor.YELLOW, NamedTextColor.AQUA,
                NamedTextColor.GREEN, NamedTextColor.RED, NamedTextColor.GOLD, NamedTextColor.BLUE, NamedTextColor.GRAY);
    }

    private void loadServices() {
        StorageMessagingHandler handler = new StorageMessagingHandler();
        ServiceLocator.register(handler);
        ConfigurationFileUtil.reloadConfig(plugin, proxy, description, handler, handler);
    }

    private void loadCommands() {
        this.commandManager.getCommandConditions().addCondition(String.class, "ip", (c, exec, value) -> {
            if(!ValidationUtil.isValidIp(value)) {
                throw new ConditionFailedException("Value must be a valid IP address.");
            }
        });

        this.commandManager.getCommandConditions().addCondition(String.class, "source", (c, exec, value) -> {
            Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
            if(!cachedConfig.isPresent()) {
                return;
            }
            for(Map.Entry<String, SourceAPI> kvp : cachedConfig.get().getSources().entrySet()) {
                if(kvp.getKey().equalsIgnoreCase(value)) {
                    return;
                }
            }
            throw new ConditionFailedException("Value must be a valid source name.");
        });

        this.commandManager.getCommandConditions().addCondition(String.class, "storage", (c, exec, value) -> {
            String v = value.replace(" ", "_");
            Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
            if(!cachedConfig.isPresent()) {
                return;
            }
            for(Storage s : cachedConfig.get().getStorage()) {
                if(s.getClass().getSimpleName().equalsIgnoreCase(v)) {
                    return;
                }
            }
            throw new ConditionFailedException("Value must be a valid storage name.");
        });

        commandManager.getCommandCompletions().registerCompletion("storage", c -> {
            String lower = c.getInput().toLowerCase().replace(" ", "_");
            Set<String> storage = new LinkedHashSet<>();
            Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
            if(!cachedConfig.isPresent()) {
                logger.error("Cached config could not be fetched.");
                return ImmutableList.copyOf(storage);
            }
            for(Storage s : cachedConfig.get().getStorage()) {
                String ss = s.getClass().getSimpleName();
                if(ss.toLowerCase().startsWith(lower)) {
                    storage.add(ss);
                }
            }
            return ImmutableList.copyOf(storage);
        });

        commandManager.getCommandCompletions().registerCompletion("player", c -> {
            String lower = c.getInput().toLowerCase();
            Set<String> players = new LinkedHashSet<>();
            for(Player p : proxy.getAllPlayers()) {
                if(lower.isEmpty() || p.getUsername().toLowerCase().startsWith(lower)) {
                    players.add(p.getUsername());
                }
            }
            return ImmutableList.copyOf(players);
        });

        commandManager.getCommandCompletions().registerCompletion("subcommand", c -> {
            String lower = c.getInput().toLowerCase();
            Set<String> commands = new LinkedHashSet<>();
            SetMultimap<String, RegisteredCommand> subcommands = commandManager.getRootCommand("antivpn").getSubCommands();
            for(Map.Entry<String, RegisteredCommand> kvp : subcommands.entries()) {
                if(!kvp.getValue().isPrivate() && (lower.isEmpty() || kvp.getKey().toLowerCase().startsWith(lower)) && kvp.getValue().getCommand().indexOf(' ') == -1) {
                    commands.add(kvp.getValue().getCommand());
                }
            }
            return ImmutableList.copyOf(commands);
        });

        commandManager.registerCommand(new AntiVPNCommand(plugin, proxy, description));
    }

    private void loadEvents() {
        this.proxy.getEventManager().register(this.plugin, new PlayerEvents(this.proxy));
    }

    private void loadTasks() {
    }

    private void loadHooks() {
        PluginManager manager = proxy.getPluginManager();

        /*if (manager.getPlugin("Plan").isPresent()) {
            consoleCommandIssuer.sendInfo(Message.GENERAL__HOOK_ENABLE, "{plugin}", "Plan");
            ServiceLocator.register(new PlayerAnalyticsHook(proxy));
        } else {
            consoleCommandIssuer.sendInfo(Message.GENERAL__HOOK_DISABLE, "{plugin}", "Plan");
        }*/
    }

    private void unloadHooks() {
        Set<? extends PluginHook> hooks = ServiceLocator.remove(PluginHook.class);
        for(PluginHook hook : hooks) {
            hook.cancel();
        }
    }

    public void unloadServices() {
        Optional<StorageMessagingHandler> storageMessagingHandler;
        try {
            storageMessagingHandler = ServiceLocator.getOptional(StorageMessagingHandler.class);
        } catch(IllegalAccessException | InstantiationException ex) {
            storageMessagingHandler = Optional.empty();
        }
        storageMessagingHandler.ifPresent(StorageMessagingHandler::close);
    }

    public boolean loadYamlLanguageFile(VelocityLocales locales, File file, Locale locale) throws IOException {
        YamlConfigurationLoader fileLoader = YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .file(file)
                .build();
        return loadLanguage(locales, fileLoader.load(), locale);
    }

    private boolean loadLanguage(VelocityLocales locales, ConfigurationNode config, Locale locale) {
        boolean loaded = false;
        for(Map.Entry<Object, ? extends ConfigurationNode> kvp : config.childrenMap().entrySet()) {
            for(Map.Entry<Object, ? extends ConfigurationNode> kvp2 : kvp.getValue().childrenMap().entrySet()) {
                String value = kvp2.getValue().getString();
                if(value != null && !value.isEmpty()) {
                    locales.addMessage(locale, MessageKey.of(kvp.getKey() + "." + kvp2.getKey()), value);
                    loaded = true;
                }
            }
        }
        return loaded;
    }
}
