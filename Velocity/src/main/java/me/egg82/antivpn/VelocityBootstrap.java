package me.egg82.antivpn;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

@Plugin(
        id = "antivpn",
        name = "AntiVPN",
        version = "5.11.37",
        authors = "egg82",
        description = "Get the best; save money on overpriced plugins and block VPN users!"
)
public class VelocityBootstrap {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ProxyServer proxy;
    private final PluginDescription description;

    private final AntiVPN concrete;

    @Inject
    public VelocityBootstrap(ProxyServer proxy, PluginDescription description) {
        this.proxy = proxy;
        this.description = description;

        if(!description.getSource().isPresent()) {
            throw new RuntimeException("Could not get plugin file path.");
        }
        if(!description.getName().isPresent()) {
            throw new RuntimeException("Could not get plugin name.");
        }

        try {
            DriverManager.registerDriver((Driver) Class.forName("org.sqlite.JDBC").newInstance());
        } catch(ClassNotFoundException | InstantiationException | IllegalAccessException | SQLException ex) {
            logger.error(ex.getMessage(), ex);
        }

        // MySQL is automatically registered
        try {
            Class.forName("com.mysql.cj.jdbc.Driver").newInstance();
        } catch(ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
            logger.error(ex.getMessage(), ex);
        }

        this.concrete = new AntiVPN(this, proxy, description);
        this.concrete.onLoad();
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onEnable(ProxyInitializeEvent event) {
        this.concrete.onEnable();
    }

    @Subscribe(order = PostOrder.LATE)
    public void onDisable(ProxyShutdownEvent event) {
        this.concrete.onDisable();
    }
}
