package me.egg82.antivpn.apis.vpn;

import me.egg82.antivpn.APIException;
import me.egg82.antivpn.utils.ValidationUtil;
import ninja.egg82.json.JSONWebUtil;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.net.URL;

public class GetIPIntel extends AbstractSourceAPI {
    public String getName() {
        return "getipintel";
    }

    public boolean isKeyRequired() {
        return false;
    }

    public boolean getResult(String ip) throws APIException {
        if(ip == null) {
            throw new IllegalArgumentException("ip cannot be null.");
        }
        if(!ValidationUtil.isValidIp(ip)) {
            throw new IllegalArgumentException("ip is invalid.");
        }

        ConfigurationNode sourceConfigNode = getSourceConfigNode();
        if(sourceConfigNode.node("contact").getString("admin@yoursite.com").equalsIgnoreCase("admin@yoursite.com")) {
            throw new APIException(true, "Contact is not defined for " + getName() + " (WARNING: USING AN INVALID E-MAIL FOR THE CONTACT WILL GET YOUR IP BANNED FROM THE SERVICE)");
        }

        JSONObject json;
        try {
            json = JSONWebUtil.getJSONObject(new URL("https://check.getipintel.net/check.php?ip=" + ip + "&contact=" + sourceConfigNode.node("contact").getString("admin@yoursite.com") + "&format=json&flags=b"), "GET", (int) getCachedConfig().getTimeout(), "egg82/AntiVPN");
        } catch(IOException | ParseException | ClassCastException ex) {
            throw new APIException(false, "Could not get result from " + getName() + " (Is your server's IP banned due to an improper contact e-mail in the config? Send an e-mail to contact@getipintel.net for an unban)", ex);
        }
        if(json == null || json.get("result") == null) {
            throw new APIException(false, "Could not get result from " + getName() + " (Is your server's IP banned due to an improper contact e-mail in the config? Send an e-mail to contact@getipintel.net for an unban)");
        }

        double retVal = Double.parseDouble((String) json.get("result"));
        if(retVal < 0.0d) {
            throw new APIException(false, "Could not get result from " + getName());
        }

        return retVal >= sourceConfigNode.node("threshold").getDouble();
    }
}
