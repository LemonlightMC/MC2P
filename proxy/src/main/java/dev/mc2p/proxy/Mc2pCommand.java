package dev.mc2p.proxy;

import com.velocitypowered.api.command.CommandSource;
import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.StringArgument;
import dev.jorel.commandapi.executors.CommandArguments;
import dev.mc2p.common.activity.ClientActivityTracker;
import dev.mc2p.common.setup.SetupSupport;
import dev.mc2p.common.tokens.ProxySecret;
import dev.mc2p.common.tokens.TokenManager;
import dev.mc2p.common.tokens.TokenManager.Token;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /mc2p} proxy console: setup, status, reload, servers, token
 * create/revoke/list.
 */
public final class Mc2pCommand {

    private final McpProxyPlugin plugin;

    public Mc2pCommand(final McpProxyPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        new CommandAPICommand("mc2p")
                .withPermission("mc2p.admin")
                .withSubcommand(new CommandAPICommand("setup")
                        .executes((final CommandSource sender, final CommandArguments args) -> setup(sender)))
                .withSubcommand(new CommandAPICommand("status")
                        .executes((final CommandSource sender, final CommandArguments args) -> status(sender)))
                .withSubcommand(new CommandAPICommand("reload")
                        .executes((final CommandSource sender, final CommandArguments args) -> reload(sender)))
                .withSubcommand(new CommandAPICommand("servers")
                        .executes((final CommandSource sender, final CommandArguments args) -> servers(sender)))
                .withSubcommand(new CommandAPICommand("activity")
                        .executes((final CommandSource sender, final CommandArguments args) -> activity(sender)))
                .withSubcommand(new CommandAPICommand("token")
                        .withSubcommand(new CommandAPICommand("create")
                                .withArguments(new StringArgument("name"))
                                .executes((final CommandSource sender, final CommandArguments args) -> create(sender,
                                        (String) args.get("name"))))
                        .withSubcommand(new CommandAPICommand("revoke")
                                .withArguments(new StringArgument("name"))
                                .executes((final CommandSource sender, final CommandArguments args) -> revoke(sender,
                                        (String) args.get("name"))))
                        .withSubcommand(new CommandAPICommand("disable")
                                .withArguments(new StringArgument("name"))
                                .executes((final CommandSource sender, final CommandArguments args) -> disable(sender,
                                        (String) args.get("name"))))
                        .withSubcommand(new CommandAPICommand("enable")
                                .withArguments(new StringArgument("name"))
                                .executes((final CommandSource sender, final CommandArguments args) -> enable(sender,
                                        (String) args.get("name"))))
                        .withSubcommand(new CommandAPICommand("list")
                                .executes((final CommandSource sender, final CommandArguments args) -> list(sender))))
                .withSubcommand(new CommandAPICommand("help")
                        .executes((final CommandSource sender, final CommandArguments args) -> help(sender)))
                .executes((final CommandSource sender, final CommandArguments args) -> help(sender))
                .register();
    }

    private void setup(final CommandSource source) {
        final var config = plugin.config();
        source.sendMessage(Component.text("[MC2P] setup", NamedTextColor.AQUA));

        for (final Map.Entry<String, Token> e : plugin.ensureTokens().entrySet()) {
            source.sendMessage(
                    Component.text("Generated token '" + e.getKey() + "' (shown once):", NamedTextColor.GREEN));
            source.sendMessage(Component.text("  " + e.getValue(), NamedTextColor.YELLOW));
        }
        for (final Token token : plugin.tokens().snapshotTokens()) {
            source.sendMessage(Component.text(
                    "  " + token.name() + " token id: " + token.tokenId() + (token.disabled() ? " (disabled)" : "")));
        }

        ProxySecret secret = ProxySecret.retrieve();
        if (secret == null) {
            secret = plugin.setupProxySecret();
            source.sendMessage(Component.text(
                    "Generated shared proxy secret (shown once) - set it on EVERY backend:", NamedTextColor.GREEN));
        } else {
            source.sendMessage(Component.text(
                    "Shared proxy secret (set it on EVERY backend exactly as shown):", NamedTextColor.GREEN));
        }
        source.sendMessage(Component.text("  " + secret, NamedTextColor.YELLOW));
        source.sendMessage(Component.text(
                "  On each backend: export " + config.rpc().secretEnv() + "=\"...\" or place it in "
                        + "plugins/MC2P/proxy-secret, then restart/reload.",
                NamedTextColor.GRAY));

        plugin.activateBackends();
        source.sendMessage(Component.text(
                "  backends activated: " + plugin.backendServerIds().size()));

        final String template = SetupSupport.clientConfigTemplate(config.mcp().port());
        try {
            Files.writeString(plugin.dataDirectory().resolve("mcpServers.json"), template);
            source.sendMessage(Component.text(
                    "Client template written to plugins/mc2p-proxy/mcpServers.json", NamedTextColor.GREEN));
        } catch (final IOException ex) {
            source.sendMessage(
                    Component.text("Could not write mcpServers.json: " + ex.getMessage(), NamedTextColor.RED));
        }
        source.sendMessage(
                Component.text("Agent mcpServers.json - replace <HOST> with your public host and <TOKEN> with a token "
                        + "with the permissions you grant the agent:"));
        source.sendMessage(Component.text("  " + template));
        source.sendMessage(Component.text(
                "Trust: with tls.mode=selfsigned, export the proxy's generated cert and pin it "
                        + "client-side; never insecureSkipVerify.",
                NamedTextColor.GRAY));
    }

    private void status(final CommandSource source) {
        final var config = plugin.config();
        source.sendMessage(Component.text("[MC2P] status", NamedTextColor.AQUA));
        source.sendMessage(Component.text("  serverId: " + plugin.serverId()));
        source.sendMessage(Component.text("  mcp endpoint: " + config.mcp().bind() + ":"
                + config.mcp().port() + config.mcp().endpoint() + " (tls="
                + config.mcp().tls().mode() + ")"));
        source.sendMessage(
                Component.text("  backends: " + plugin.backendServerIds().size()));
        source.sendMessage(Component.text("  tools registered: " + plugin.toolCount()));
        for (final Token token : plugin.tokens().snapshotTokens()) {
            source.sendMessage(Component.text(
                    "  token " + token.name() + ": " + token.tokenId() + (token.disabled() ? " (disabled)" : "")));
        }
        source.sendMessage(Component.text("  audit log: " + config.audit().file()));
    }

    private void reload(final CommandSource source) {
        try {
            plugin.reload();
            source.sendMessage(Component.text("[MC2P] Configuration reloaded.", NamedTextColor.GREEN));
        } catch (final RuntimeException e) {
            source.sendMessage(Component.text("[MC2P] Reload failed: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void servers(final CommandSource source) {
        final var ids = plugin.backendServerIds();
        if (ids.isEmpty()) {
            source.sendMessage(Component.text("[MC2P] No backends connected."));
            return;
        }
        source.sendMessage(Component.text("[MC2P] Connected backends:"));
        for (final String id : ids) {
            source.sendMessage(Component.text("  " + id));
        }
    }

    private void activity(final CommandSource source) {
        final java.util.List<ClientActivityTracker.Entry> active = plugin.activity().active();
        final int windowMinutes = plugin.config().auth().activityWindowMinutes();
        source.sendMessage(
                Component.text("[MC2P] Active clients (last " + windowMinutes + " min):", NamedTextColor.AQUA));
        if (active.isEmpty()) {
            source.sendMessage(Component.text("  none"));
            return;
        }
        for (final ClientActivityTracker.Entry e : active) {
            source.sendMessage(Component.text("  " + e.name() + " " + e.remoteIp() + " requests=" + e.requestCount()
                    + " last=" + relativeTime(e.lastSeenMillis())));
        }
    }

    private static String relativeTime(final long millis) {
        final long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        return (seconds / 60) + "m " + (seconds % 60) + "s ago";
    }

    private void create(final CommandSource source, final String name) {
        if (!TokenManager.isValidName(name)) {
            source.sendMessage(Component.text(
                    "[MC2P] Invalid token name: " + name + " (use letters, digits, - and _, max 40)",
                    NamedTextColor.RED));
            return;
        }
        final Token token = plugin.tokens().create(name);
        plugin.audit().log(null, "console", plugin.serverId(), "token", "create", "{\"name\":\"" + name + "\"}");
        source.sendMessage(Component.text("[MC2P] New token '" + name + "', shown once:", NamedTextColor.GREEN));
        source.sendMessage(Component.text(token.tokenId(), NamedTextColor.YELLOW));
    }

    private void revoke(final CommandSource source, final String name) {
        final boolean revoked = plugin.tokens().revoke(name);
        plugin.audit().log(null, "console", plugin.serverId(), "token", "revoke", "{\"name\":\"" + name + "\"}");
        if (revoked) {
            source.sendMessage(Component.text("[MC2P] Token '" + name + "' revoked.", NamedTextColor.GREEN));
        } else {
            source.sendMessage(
                    Component.text("[MC2P] No token named '" + name + "' to revoke.", NamedTextColor.YELLOW));
        }
    }

    private void disable(final CommandSource source, final String name) {
        final boolean changed = plugin.tokens().disable(name);
        plugin.audit().log(null, "console", plugin.serverId(), "token", "disable", "{\"name\":\"" + name + "\"}");
        if (changed) {
            source.sendMessage(Component.text("[MC2P] Token '" + name + "' disabled.", NamedTextColor.GREEN));
        } else {
            source.sendMessage(
                    Component.text("[MC2P] No active token named '" + name + "' to disable.", NamedTextColor.YELLOW));
        }
    }

    private void enable(final CommandSource source, final String name) {
        final boolean changed = plugin.tokens().enable(name);
        plugin.audit().log(null, "console", plugin.serverId(), "token", "enable", "{\"name\":\"" + name + "\"}");
        if (changed) {
            source.sendMessage(Component.text("[MC2P] Token '" + name + "' enabled.", NamedTextColor.GREEN));
        } else {
            source.sendMessage(
                    Component.text("[MC2P] No disabled token named '" + name + "' to enable.", NamedTextColor.YELLOW));
        }
    }

    private void list(final CommandSource source) {
        final List<Token> snapshot = plugin.tokens().snapshotTokens();
        if (snapshot.isEmpty()) {
            source.sendMessage(Component.text("[MC2P] No tokens configured. Run /mc2p token create <name>."));
            return;
        }
        source.sendMessage(Component.text("[MC2P] Tokens:", NamedTextColor.AQUA));
        for (final Token token : snapshot) {
            source.sendMessage(
                    Component.text(
                            "  " + token.name() + " " + token.tokenId() + (token.disabled() ? " (disabled)" : "")));
        }
    }

    private void help(final CommandSource source) {
        source.sendMessage(Component.text("[MC2P] Usage:"));
        source.sendMessage(Component.text("  /mc2p setup"));
        source.sendMessage(Component.text("  /mc2p status"));
        source.sendMessage(Component.text("  /mc2p reload"));
        source.sendMessage(Component.text("  /mc2p servers"));
        source.sendMessage(Component.text("  /mc2p activity"));
        source.sendMessage(Component.text("  /mc2p token create <name>"));
        source.sendMessage(Component.text("  /mc2p token revoke <name>"));
        source.sendMessage(Component.text("  /mc2p token disable <name>"));
        source.sendMessage(Component.text("  /mc2p token enable <name>"));
        source.sendMessage(Component.text("  /mc2p token list"));
    }
}
