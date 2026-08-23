package dev.mc2p.plugin;

import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIPaperConfig;
import dev.mc2p.common.StateHolder;
import dev.mc2p.common.activity.ActivityLogger;
import dev.mc2p.common.activity.ClientActivityTracker;
import dev.mc2p.common.config.ConfigSupport;
import dev.mc2p.common.http.McpHttpServer;
import dev.mc2p.common.tokens.ProxySecret;
import dev.mc2p.common.tokens.TokenManager;
import dev.mc2p.common.tokens.TokenManager.Token;
import dev.mc2p.plugin.config.BackendConfig;
import dev.mc2p.plugin.config.ConfigFiles;
import dev.mc2p.plugin.facade.PaperServerFacade;
import dev.mc2p.plugin.http.HealthzServlet;
import dev.mc2p.plugin.rpc.BackendRpcServer;
import dev.mc2p.plugin.thread.MainThread;
import dev.mc2p.plugin.tools.McpServerBootstrap;
import dev.mc2p.plugin.tools.ReadTools;
import dev.mc2p.plugin.tools.ToolInvoker;
import dev.mc2p.plugin.tools.ToolRegistry;
import dev.mc2p.plugin.tools.WriteTools;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The MC2P backend Paper plugin. Serves as a standalone MCP server (single TLS
 * port) or,
 * behind a proxy, as a zero-port RPC backend over {@code mc2p:rpc}.
 */
public final class Mc2pPlugin extends JavaPlugin implements StateHolder<BackendConfig> {

    private static final Logger log = LoggerFactory.getLogger(Mc2pPlugin.class);

    private BackendConfig config;
    private TokenManager tokens;
    private ClientActivityTracker activity;
    private ActivityLogger audit;
    private MainThread mainThread;
    private PaperServerFacade facade;
    private ToolRegistry registry;
    private ToolInvoker invoker;
    private McpHttpServer httpServer;
    private BackendRpcServer rpcServer;
    private String mode;

    @Override
    public void onLoad() {
        CommandAPI.onLoad(new CommandAPIPaperConfig(this));
    }

    @Override
    public void onEnable() {
        CommandAPI.onEnable();
        try {
            ConfigFiles.ensureInitialConfig(this, getDataFolder().toPath());
            init();
        } catch (final RuntimeException e) {
            log.error("MC2P failed to start: {}", e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        new Mc2pCommand(this).register();
        getServer().getPluginManager().registerEvents(new PlayerTracker(this), this);
        log.info("MC2P enabled in {} mode (serverId={})", mode, config.serverId());
    }

    @Override
    public void onDisable() {
        teardown();
        CommandAPI.unregister("mc2p");
        CommandAPI.onDisable();
        log.info("MC2P disabled");
    }

    /**
     * Applies (or re-applies) the configuration; fully tears down and rebuilds the
     * runtime.
     */
    public void init() {
        teardown();
        final Path dataDir = getDataFolder().toPath();
        final Path configFile = ConfigFiles.activeConfigFile(dataDir);
        config = BackendConfig.load(loadConfigYaml(configFile));
        mode = resolveMode(config, configFile);

        if ("standalone".equals(mode) && !getServer().getOnlineMode()) {
            log.error("MC2P refuses to start in standalone mode: online-mode=false allows name spoofing. "
                    + "Enable online-mode=true, or run behind an authenticating proxy (mode: backend).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if ("backend".equals(mode)) {
            ProxySecret.resolve(config().rpc().secretEnv(), dataDirectory());

            if (!ProxySecret.isPresent()) {
                log.error(
                        "MC2P backend mode: no proxy secret is set ({} env var or plugins/MC2P/proxy-secret). "
                                + "Set the same secret here and on the proxy, or run /mc2p setup on the proxy to "
                                + "generate one. Disabling the MCP backend.",
                        config.rpc().secretEnv());
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

        }

        tokens = new TokenManager(dataDir.resolve("tokens.yml"), dataDir);
        tokens.load();
        activity = new ClientActivityTracker(config.auth().activityWindowMinutes());

        if ("standalone".equals(mode)) {
            provisionMissingTokens();
        }

        audit = new ActivityLogger(
                dataDir.resolve(config.audit().file()),
                config.audit().maxMb(),
                config.audit().maxFiles());

        mainThread = new MainThread(this, config.rpc().timeoutMs());
        facade = new PaperServerFacade(this, mainThread, config.serverId(), config.restartStrategy());

        registry = new ToolRegistry();
        ReadTools.register(registry, facade, config);
        WriteTools.register(registry, facade, config);
        invoker = new ToolInvoker(registry, audit, config.serverId());

        if ("backend".equals(mode)) {
            startBackendMode(dataDir);
        } else {
            startStandalone(dataDir);
        }
    }

    private void startStandalone(final Path dataDir) {
        httpServer = new McpHttpServer(this, config.effectiveRestrictions(),
                (transport) -> {
                    return McpServerBootstrap.build(
                            registry, facade, invoker, transport, getPluginMeta().getVersion(), mainThread);
                },
                () -> {
                    return new HealthzServlet(config.serverId(), getPluginMeta().getVersion(), mode,
                            config.restartStrategy());
                });
        httpServer.start();
    }

    private void startBackendMode(final Path dataDir) {
        rpcServer = new BackendRpcServer(
                this,
                invoker,
                config.effectiveRestrictions(),
                config.serverId(),
                config.rpc().channel(),
                ProxySecret.retrieve().value(),
                config.rpc().timeoutMs());
        getServer()
                .getMessenger()
                .registerIncomingPluginChannel(this, config.rpc().channel(), rpcServer);
        getServer()
                .getMessenger()
                .registerOutgoingPluginChannel(this, config.rpc().channel());
        log.info("MC2P backend registered on plugin channel {}", config.rpc().channel());
    }

    public void teardown() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (rpcServer != null) {
            getServer()
                    .getMessenger()
                    .unregisterIncomingPluginChannel(this, config.rpc().channel());
            getServer()
                    .getMessenger()
                    .unregisterOutgoingPluginChannel(this, config.rpc().channel());
            rpcServer = null;
        }
    }

    /**
     * Determines the effective mode: standalone | backend (auto: backend behind a
     * known proxy).
     */
    private String resolveMode(final BackendConfig config, final Path configFile) {
        if (ConfigFiles.BACKEND_FILE.equals(configFile.getFileName().toString())) {
            return "backend";
        }
        final String configured = config.mode();
        if (!"auto".equals(configured)) {
            return configured;
        }
        final boolean behindProxy = isBehindBungee() || ProxySecret.isPresent();
        return behindProxy ? "backend" : "standalone";
    }

    private boolean isBehindBungee() {
        try {
            final Path spigotYml = getDataFolder().getParentFile().toPath().resolve("spigot.yml");
            if (java.nio.file.Files.isRegularFile(spigotYml)) {
                final YamlConfiguration spigot = YamlConfiguration.loadConfiguration(spigotYml.toFile());
                return spigot.getBoolean("settings.bungeecord", false);
            }
        } catch (final Exception ignored) {
        }
        return false;
    }

    private Map<String, Object> loadConfigYaml(final Path configFile) {
        try {
            final Map<String, Object> parsed = ConfigSupport.loadYaml(configFile);
            if (parsed.isEmpty()) {
                log.warn(
                        "MC2P: {} is missing or empty; using defaults. Run /mc2p status to verify.",
                        configFile.getFileName());
            }
            return parsed;
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read " + configFile.getFileName(), e);
        }
    }

    // ---- accessors for Mc2pCommand ----

    public Path dataDirectory() {
        return getDataFolder().toPath();
    }

    public String effectiveMode() {
        return mode;
    }

    public BackendConfig config() {
        return config;
    }

    public TokenManager tokens() {
        return tokens;
    }

    public ClientActivityTracker activity() {
        return activity;
    }

    public ActivityLogger audit() {
        return audit;
    }

    public ToolRegistry registry() {
        return registry;
    }

    public String serverId() {
        return config == null ? "?" : config.serverId();
    }

    public boolean isRestarting() {
        return false;
    }

    public Logger logger() {
        return log;
    }

    /**
     * Creates a default-named token if the store has no active tokens and returns
     * the freshly generated plaintext (shown exactly once).
     */
    public Map<String, Token> ensureTokens() {
        final Map<String, Token> generated = new java.util.LinkedHashMap<>();
        if (!tokens.snapshot().isEmpty()) {
            return generated;
        }
        generated.put("default", tokens.create("default"));
        return generated;
    }

    /**
     * Auto-provisions missing API tokens on first standalone run; logs them once.
     */
    private void provisionMissingTokens() {
        final Map<String, Token> generated = ensureTokens();
        if (generated.isEmpty()) {
            return;
        }
        log.info("MC2P: no API tokens configured; generated the following (shown once):");
        for (final Map.Entry<String, Token> e : generated.entrySet()) {
            log.info("  {}: {}", e.getKey(), e.getValue());
        }
        log.info("MC2P: run /mc2p setup to print the agent client config for this server.");
    }

    /**
     * Sends the resource-list-changed notification to connected agents (off the
     * main
     * thread). In backend mode the notification is relayed to the proxy as an RPC
     * push.
     */
    public void notifyPlayersChanged() {
        if (rpcServer != null) {
            try {
                rpcServer.notifyEvent("players", Map.of());
            } catch (final RuntimeException e) {
                log.warn("MC2P: failed to push player change to proxy: {}", e.getMessage());
            }
            return;
        }
        final McpSyncServer server = httpServer == null ? null : httpServer.mcpSyncServer();
        if (server == null) {
            return;
        }
        final Thread notifier = new Thread(
                () -> {
                    try {
                        server.notifyResourcesListChanged();
                    } catch (final RuntimeException e) {
                        log.warn("MC2P: failed to notify resource list change: {}", e.getMessage());
                    }
                },
                "mc2p-notify");
        notifier.setDaemon(true);
        notifier.start();
    }
}
