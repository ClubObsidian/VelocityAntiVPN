package me.egg82.antivpn.events;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import inet.ipaddr.IPAddressString;
import me.egg82.antivpn.APIException;
import me.egg82.antivpn.VPNAPI;
import me.egg82.antivpn.enums.VPNAlgorithmMethod;
import me.egg82.antivpn.extended.CachedConfigValues;
import me.egg82.antivpn.services.AnalyticsHelper;
import me.egg82.antivpn.services.lookup.PlayerInfo;
import me.egg82.antivpn.services.lookup.PlayerLookup;
import me.egg82.antivpn.utils.ConfigUtil;
import me.egg82.antivpn.utils.LogUtil;
import me.egg82.antivpn.utils.ValidationUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PlayerEvents {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final VPNAPI api = VPNAPI.getInstance();
    private final ProxyServer proxy;

    public PlayerEvents(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe(order = PostOrder.LATE)
    public void cachePlayer(PreLoginEvent event) {
        String ip = getIp(event.getConnection().getRemoteAddress());
        if(ip == null || ip.isEmpty()) {
            return;
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if(!cachedConfig.isPresent()) {
            return;
        }

        UUID playerID = getPlayerUUID(event.getUsername(), proxy);
        if(playerID == null) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(ip).color(NamedTextColor.WHITE)).append(Component.text(" is using an invalid player name ").color(NamedTextColor.YELLOW)).append(Component.text(event.getUsername()).color(NamedTextColor.WHITE)).append(Component.text(". Skipping pre-login cache and letting Velocity validate the request before checking.").color(NamedTextColor.YELLOW)));
            return;
        }

        for(String testAddress : cachedConfig.get().getIgnoredIps()) {
            if(
                    ValidationUtil.isValidIp(testAddress) && ip.equalsIgnoreCase(testAddress)
                            || ValidationUtil.isValidIPRange(testAddress) && rangeContains(testAddress, ip)
            ) {
                return;
            }
        }

        if((!cachedConfig.get().getVPNKickMessage().isEmpty() || !cachedConfig.get().getVPNActionCommands().isEmpty())) {
            if(cachedConfig.get().getVPNAlgorithmMethod() == VPNAlgorithmMethod.CONSESNSUS) {
                try {
                    api.consensus(ip); // Calling this will cache the result internally, even if the value is unused
                } catch(APIException ex) {
                    logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
                }
            } else {
                try {
                    api.cascade(ip); // Calling this will cache the result internally, even if the value is unused
                } catch(APIException ex) {
                    logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
                }
            }
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    public void checkPlayer(PostLoginEvent event) {
        String ip = getIp(event.getPlayer().getRemoteAddress());
        if(ip == null || ip.isEmpty()) {
            return;
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if(!cachedConfig.isPresent()) {
            return;
        }

        if(event.getPlayer().hasPermission("avpn.bypass")) {
            if(ConfigUtil.getDebugOrFalse()) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(event.getPlayer().getUsername()).color(NamedTextColor.WHITE)).append(Component.text(" bypasses check. Ignoring.").color(NamedTextColor.YELLOW)));
            }
            return;
        }

        for(String testAddress : cachedConfig.get().getIgnoredIps()) {
            if(ValidationUtil.isValidIp(testAddress) && ip.equalsIgnoreCase(testAddress)) {
                if(ConfigUtil.getDebugOrFalse()) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(event.getPlayer().getUsername()).color(NamedTextColor.WHITE)).append(Component.text(" is using an ignored IP ").color(NamedTextColor.YELLOW)).append(Component.text(ip).color(NamedTextColor.WHITE)).append(Component.text(". Ignoring.").color(NamedTextColor.YELLOW)));
                }
                return;
            } else if(ValidationUtil.isValidIPRange(testAddress) && rangeContains(testAddress, ip)) {
                if(ConfigUtil.getDebugOrFalse()) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(event.getPlayer().getUsername()).color(NamedTextColor.WHITE)).append(Component.text(" is under an ignored range ").color(NamedTextColor.YELLOW)).append(Component.text(testAddress + " (" + ip + ")").color(NamedTextColor.WHITE)).append(Component.text(". Ignoring.").color(NamedTextColor.YELLOW)));
                }
                return;
            }
        }

        if(!cachedConfig.get().getVPNKickMessage().isEmpty() || !cachedConfig.get().getVPNActionCommands().isEmpty()) {
            boolean isVPN;

            if(cachedConfig.get().getVPNAlgorithmMethod() == VPNAlgorithmMethod.CONSESNSUS) {
                try {
                    isVPN = api.consensus(ip) >= cachedConfig.get().getVPNAlgorithmConsensus();
                } catch(APIException ex) {
                    logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
                    isVPN = false;
                }
            } else {
                try {
                    isVPN = api.cascade(ip);
                } catch(APIException ex) {
                    logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
                    isVPN = false;
                }
            }

            if(isVPN) {
                AnalyticsHelper.incrementBlockedVPNs();
                if(ConfigUtil.getDebugOrFalse()) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(event.getPlayer().getUsername()).color(NamedTextColor.WHITE)).append(Component.text(" found using a VPN. Running required actions.").color(NamedTextColor.DARK_RED)));
                }

                tryRunCommands(cachedConfig.get().getVPNActionCommands(), event.getPlayer(), ip);
                tryKickPlayer(cachedConfig.get().getVPNKickMessage(), event.getPlayer(), event);
            } else {
                if(ConfigUtil.getDebugOrFalse()) {
                    proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text(event.getPlayer().getUsername()).color(NamedTextColor.WHITE)).append(Component.text(" passed VPN check.").color(NamedTextColor.GREEN)));
                }
            }
        } else {
            if(ConfigUtil.getDebugOrFalse()) {
                proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(Component.text("VPN set to API-only. Ignoring VPN check for ").color(NamedTextColor.YELLOW)).append(Component.text(event.getPlayer().getUsername()).color(NamedTextColor.WHITE)));
            }
        }
    }

    private void tryRunCommands(List<String> commands, Player player, String ip) {
        for(String command : commands) {
            command = command.replace("%player%", player.getUsername()).replace("%uuid%", player.getUniqueId().toString()).replace("%ip%", ip);
            if(command.charAt(0) == '/') {
                command = command.substring(1);
            }

            proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command);
        }
    }

    private UUID getPlayerUUID(String name, ProxyServer proxy) {
        PlayerInfo info;
        try {
            info = PlayerLookup.get(name, proxy);
        } catch(IOException ex) {
            logger.warn("Could not fetch player UUID. (rate-limited?)", ex);
            return null;
        }
        return info.getUUID();
    }

    private void tryKickPlayer(String message, Player player, PostLoginEvent event) {
        player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    private String getIp(InetSocketAddress address) {
        if(address == null) {
            return null;
        }
        InetAddress host = address.getAddress();
        if(host == null) {
            return null;
        }
        return host.getHostAddress();
    }

    private boolean rangeContains(String range, String ip) {
        return new IPAddressString(range).contains(new IPAddressString(ip));
    }
}
