package me.egg82.antivpn.apis;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import ninja.egg82.json.JSONWebUtil;
import ninja.leaping.configurate.ConfigurationNode;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IPHubAPI implements API {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public String getName() { return "iphub"; }

    public Optional<Boolean> getResult(String ip, ConfigurationNode sourceConfigNode) {
        if (ip == null) {
            throw new IllegalArgumentException("ip cannot be null.");
        }
        if (sourceConfigNode == null) {
            throw new IllegalArgumentException("sourceConfigNode cannot be null.");
        }

        String key = sourceConfigNode.getNode("key").getString();
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }

        int blockType = sourceConfigNode.getNode("block").getInt(1);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Key", key);

        JSONObject json;
        try {
            json = JSONWebUtil.getJsonObject("https://v2.api.iphub.info/ip/" + ip, "egg82/AntiVPN", headers);
        } catch (IOException | ParseException ex) {
            logger.error(ex.getMessage(), ex);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }

        int block = ((Number) json.get("block")).intValue();
        return Optional.of((block == blockType) ? Boolean.TRUE : Boolean.FALSE);
    }
}
