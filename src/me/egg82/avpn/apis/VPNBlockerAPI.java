package me.egg82.avpn.apis;

import java.util.Optional;

import org.json.simple.JSONObject;

import me.egg82.avpn.utils.WebUtil;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.patterns.ServiceLocator;

public class VPNBlockerAPI {
	//vars
	
	//constructor
	public VPNBlockerAPI() {
		
	}
	
	//public
	public static Optional<Boolean> isVPN(String ip) {
		String key = ServiceLocator.getService(ConfigRegistry.class).getRegister("sources.vpnblocker.key", String.class);
		
		JSONObject json = WebUtil.getJson("http" + ((key != null && !key.isEmpty()) ? "s" : "") + "://api.vpnblocker.net/v2/json/" + ip + ((key != null && !key.isEmpty()) ? "/" + key : ""));
		if (json == null) {
			return Optional.empty();
		}
		
		String status = (String) json.get("status");
		if (!status.equalsIgnoreCase("success")) {
			return Optional.empty();
		}
		
		return Optional.of((((Boolean) json.get("host-ip")).booleanValue()) ? Boolean.TRUE : Boolean.FALSE);
	}
	
	//private
	
}
