package me.egg82.antivpn;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.xml.xpath.XPathExpressionException;
import me.egg82.antivpn.utils.LogUtil;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import ninja.egg82.maven.Artifact;
import ninja.egg82.maven.Repository;
import ninja.egg82.maven.Scope;
import ninja.egg82.services.ProxiedURLClassLoader;
import ninja.egg82.utils.InjectUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

@Plugin(
        id = "antivpn",
        name = "AntiVPN",
        version = "5.11.37",
        authors = "egg82",
        description = "Get the best; save money on overpriced plugins and block VPN users!",
        dependencies = @Dependency(id = "plan", optional = true)
)
public class VelocityBootstrap {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private ProxyServer proxy;
    private PluginDescription description;

    private Object concrete;
    private Class<?> concreteClass;

    private URLClassLoader proxiedClassLoader;
    private final ExecutorService downloadPool = Executors.newWorkStealingPool(Math.max(4, Runtime.getRuntime().availableProcessors()));

    @Inject
    public VelocityBootstrap(ProxyServer proxy, PluginDescription description) {
        this.proxy = proxy;
        this.description = description;

        if (!description.getSource().isPresent()) {
            throw new RuntimeException("Could not get plugin file path.");
        }
        if (!description.getName().isPresent()) {
            throw new RuntimeException("Could not get plugin name.");
        }

        proxiedClassLoader = new ProxiedURLClassLoader(getClass().getClassLoader(), new String[] { "org\\.slf4j\\..*", "com\\.mysql\\.jdbc\\..*" });

        try {
            loadJars(new File(new File(description.getSource().get().getParent().toFile(), description.getName().get()), "external"), proxiedClassLoader);
        } catch (ClassCastException | IOException | IllegalAccessException | InvocationTargetException ex) {
            logger.error(ex.getMessage(), ex);
            throw new RuntimeException("Could not load required deps.");
        }

        downloadPool.shutdown();
        try {
            if (!downloadPool.awaitTermination(1L, TimeUnit.HOURS)) {
                logger.error("Could not download all dependencies. Please try again later.");
                return;
            }
        } catch (InterruptedException ex) {
            logger.error(ex.getMessage(), ex);
            Thread.currentThread().interrupt();
        }

        try {
            DriverManager.registerDriver((Driver) Class.forName("org.sqlite.JDBC", true, proxiedClassLoader).newInstance());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | SQLException ex) {
            logger.error(ex.getMessage(), ex);
        }

        // MySQL is automatically registered
        try {
            Class.forName("com.mysql.cj.jdbc.Driver", true, proxiedClassLoader).newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
            logger.error(ex.getMessage(), ex);
        }

        try {
            concreteClass = proxiedClassLoader.loadClass("me.egg82.antivpn.AntiVPN");
            concrete = concreteClass.getDeclaredConstructor(Object.class, ProxyServer.class, PluginDescription.class).newInstance(this, proxy, description);
            concreteClass.getMethod("onLoad").invoke(concrete);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException ex) {
            logger.error(ex.getMessage(), ex);
            throw new RuntimeException("Could not create main class.");
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onEnable(ProxyInitializeEvent event) {
        try {
            concreteClass.getMethod("onEnable").invoke(concrete);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            logger.error(ex.getMessage(), ex);
            throw new RuntimeException("Could not invoke onEnable.");
        }

        if (ExternalAPI.getInstance() == null) {
            ExternalAPI.setInstance(proxiedClassLoader);
        }
    }

    @Subscribe(order = PostOrder.LATE)
    public void onDisable(ProxyShutdownEvent event) {
        try {
            concreteClass.getMethod("onDisable").invoke(concrete);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            logger.error(ex.getMessage(), ex);
            throw new RuntimeException("Could not invoke onDisable.");
        }
    }

    private void loadJars(File jarsDir, URLClassLoader classLoader) throws IOException, IllegalAccessException, InvocationTargetException {
        if (jarsDir.exists() && !jarsDir.isDirectory()) {
            Files.delete(jarsDir.toPath());
        }
        if (!jarsDir.exists()) {
            if (!jarsDir.mkdirs()) {
                throw new IOException("Could not create parent directory structure.");
            }
        }

        File cacheDir = new File(jarsDir, "cache");

        // First

        Artifact.Builder guava = Artifact.builder("com.google.guava", "guava", "28.2-jre", cacheDir)
                .addRepository(Repository.builder("https://repo1.maven.org/maven2/").addProxy("https://nexus.egg82.me/repository/maven-central/").build());
        buildInjectWait(guava, jarsDir, classLoader, "Google Guava", 1);

        // Same file

        InjectUtil.injectFile(description.getSource().get().toFile(), classLoader);

    }

    private void printLatest(String friendlyName) {
        proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Checking version of ").color(TextColor.YELLOW)).append(TextComponent.of(friendlyName).color(TextColor.WHITE)).build());
    }

    private void buildInject(Artifact.Builder builder, File jarsDir, URLClassLoader classLoader, String friendlyName) {
        buildInject(builder, jarsDir, classLoader, friendlyName, 0);
    }

    private void buildInject(Artifact.Builder builder, File jarsDir, URLClassLoader classLoader, String friendlyName, int depth) {
        downloadPool.submit(() -> buildInjectWait(builder, jarsDir, classLoader, friendlyName, depth));
    }

    private void buildInjectWait(Artifact.Builder builder, File jarsDir, URLClassLoader classLoader, String friendlyName, int depth) {
        try {
            injectArtifact(builder.build(), jarsDir, classLoader, friendlyName, depth);
        } catch (IOException | IllegalAccessException | InvocationTargetException | URISyntaxException | XPathExpressionException | SAXException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    private void injectArtifact(Artifact artifact, File jarsDir, URLClassLoader classLoader, String friendlyName, int depth) throws IOException, IllegalAccessException, InvocationTargetException, URISyntaxException, XPathExpressionException, SAXException {
        File output = new File(jarsDir, artifact.getGroupId()
                + "-" + artifact.getArtifactId()
                + "-" + artifact.getRealVersion() + ".jar"
        );

        if (friendlyName != null && !artifact.fileExists(output)) {
            proxy.getConsoleCommandSource().sendMessage(LogUtil.getHeading().append(TextComponent.of("Downloading ").color(TextColor.YELLOW)).append(TextComponent.of(friendlyName).color(TextColor.WHITE)).build());
        }
        artifact.injectJar(output, classLoader);

        if (depth > 0) {
            for (Artifact dependency : artifact.getDependencies()) {
                if (dependency.getScope() == Scope.COMPILE || dependency.getScope() == Scope.RUNTIME) {
                    injectArtifact(dependency, jarsDir, classLoader, null, depth - 1);
                }
            }
        }
    }
}
