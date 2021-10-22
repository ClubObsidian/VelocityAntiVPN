package me.egg82.antivpn.utils;

import com.google.common.io.Files;
import com.google.common.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public class ConfigurationVersionUtil {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationVersionUtil.class);

    private ConfigurationVersionUtil() {}

    public static void conformVersion(YamlConfigurationLoader loader, ConfigurationNode config, File fileOnDisk) throws IOException {
        double oldVersion = config.node("version").getDouble(1.0d);
        if (config.node("version").getDouble() == 4.12d) {
            to413(config);
        }

        if (config.node("version").getDouble() != oldVersion) {
            File backupFile = new File(fileOnDisk.getParent(), fileOnDisk.getName() + ".bak");
            if (backupFile.exists()) {
                java.nio.file.Files.delete(backupFile.toPath());
            }

            Files.copy(fileOnDisk, backupFile);
            loader.save(config);
        }
    }

    private static void to413(ConfigurationNode config) {
        // Add private ranges to ignore
        List<String> ignoredIPs = new ArrayList<>();
        try {
            ignoredIPs = new ArrayList<>(config.node("action", "ignore").getList(String.class));
        } catch(SerializationException e) {
            e.printStackTrace();
        }

        List<String> added = new ArrayList<>();
        added.add("10.0.0.0/8");
        added.add("172.16.0.0/12");
        added.add("192.168.0.0/16");
        added.add("fd00::/8");

        for (Iterator<String> i = added.iterator(); i.hasNext();) {
            String ip = i.next();
            for (String ip2 : ignoredIPs) {
                if (ip.equalsIgnoreCase(ip2)) { // IPs are case-insensitive when loaded
                    i.remove();
                }
            }
        }

        ignoredIPs.addAll(added);
        try {
            config.node("action", "ignore").set(ignoredIPs);
            // Version
            config.node("version").set(4.13d);
        } catch(SerializationException e) {
            e.printStackTrace();
        }
    }
}
