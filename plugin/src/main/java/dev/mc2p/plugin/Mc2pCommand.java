package dev.mc2p.plugin;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.StringArgument;
import dev.jorel.commandapi.executors.CommandArguments;
import dev.mc2p.common.activity.ClientActivityTracker;
import dev.mc2p.common.setup.SetupSupport;
import dev.mc2p.common.tokens.ProxySecret;
import dev.mc2p.common.tokens.TokenManager;
import dev.mc2p.common.tokens.TokenManager.Token;
import dev.mc2p.plugin.config.BackendConfig;
import dev.mc2p.plugin.config.ConfigFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/**
 * {@code /mc2p} admin console: setup, status, reload, mode, activity, token
 * create/revoke/disable/enable/list.
 */
public final class Mc2pCommand {

        private static final Component PREFIX = Component.text("[MC2P] ", NamedTextColor.AQUA);

        private final Mc2pPlugin plugin;

        public Mc2pCommand(final Mc2pPlugin plugin) {
                this.plugin = plugin;
        }

        public void register() {
                new CommandAPICommand("mc2p")
                                .withPermission("mc2p.admin")
                                .withSubcommand(new CommandAPICommand("setup")
                                                .executes((final CommandSender sender,
                                                                final CommandArguments args) -> setup(sender)))
                                .withSubcommand(new CommandAPICommand("status")
                                                .executes((final CommandSender sender,
                                                                final CommandArguments args) -> status(sender)))
                                .withSubcommand(new CommandAPICommand("reload")
                                                .executes((final CommandSender sender,
                                                                final CommandArguments args) -> reload(sender)))
                                .withSubcommand(new CommandAPICommand("mode")
                                                .withArguments(new StringArgument("mode"))
                                                .executes((final CommandSender sender,
                                                                final CommandArguments args) -> mode(sender,
                                                                                (String) args.get("mode"))))
                                .withSubcommand(new CommandAPICommand("activity")
                                                .executes((final CommandSender sender,
                                                                final CommandArguments args) -> activity(sender)))
                                .withSubcommand(new CommandAPICommand("token")
                                                .withSubcommand(new CommandAPICommand("create")
                                                                .withArguments(new StringArgument("name"))
                                                                .executes((final CommandSender sender,
                                                                                final CommandArguments args) -> create(
                                                                                                sender,
                                                                                                (String) args.get(
                                                                                                                "name"))))
                                                .withSubcommand(new CommandAPICommand("revoke")
                                                                .withArguments(new StringArgument("name"))
                                                                .executes((final CommandSender sender,
                                                                                final CommandArguments args) -> revoke(
                                                                                                sender,
                                                                                                (String) args.get(
                                                                                                                "name"))))
                                                .withSubcommand(new CommandAPICommand("disable")
                                                                .withArguments(new StringArgument("name"))
                                                                .executes((final CommandSender sender,
                                                                                final CommandArguments args) -> disable(
                                                                                                sender,
                                                                                                (String) args.get(
                                                                                                                "name"))))
                                                .withSubcommand(new CommandAPICommand("enable")
                                                                .withArguments(new StringArgument("name"))
                                                                .executes((final CommandSender sender,
                                                                                final CommandArguments args) -> enable(
                                                                                                sender,
                                                                                                (String) args.get(
                                                                                                                "name"))))
                                                .withSubcommand(new CommandAPICommand("list")
                                                                .executes((final CommandSender sender,
                                                                                final CommandArguments args) -> list(
                                                                                                sender))))
                                .withSubcommand(new CommandAPICommand("help")
                                                .executes((final CommandSender sender,
                                                                final CommandArguments args) -> sendHelp(sender)))
                                .executes((final CommandSender sender, final CommandArguments args) -> sendHelp(sender))
                                .register();
        }

        private void setup(final CommandSender sender) {
                final BackendConfig config = plugin.config();
                if ("backend".equals(plugin.effectiveMode())) {
                        if (!ProxySecret.isPresent()) {
                                sender.sendMessage(PREFIX.append(Component.text(
                                                "Backend mode: no proxy secret is set. "
                                                                + "Set " + config.proxy().secretEnv()
                                                                + " or place plugins/MC2P/proxy-secret, "
                                                                + "then run /mc2p reload.",
                                                NamedTextColor.RED)));
                                return;
                        }
                        sender.sendMessage(PREFIX.append(Component.text("MC2P backend is active.")));
                        sender.sendMessage(Component.text("  serverId: ", NamedTextColor.GRAY)
                                        .append(Component.text(plugin.serverId(), NamedTextColor.WHITE)));
                        sender.sendMessage(Component.text("  rpc channel: ", NamedTextColor.GRAY)
                                        .append(Component.text(config.proxy().rpcChannel(), NamedTextColor.WHITE)));
                        sender.sendMessage(Component.text("  proxy secret: ", NamedTextColor.GRAY)
                                        .append(Component.text("set", NamedTextColor.GREEN)));
                        sender.sendMessage(PREFIX
                                        .append(Component.text("Backends hold no API tokens - the proxy owns them. "
                                                        + "Agent config lives on the proxy (run /mc2p setup there).")));
                        return;
                }

                sender.sendMessage(PREFIX.append(Component.text("MC2P setup (standalone)")));
                for (final Map.Entry<String, Token> e : plugin.ensureTokens().entrySet()) {
                        sender.sendMessage(PREFIX.append(
                                        Component.text("Generated token '" + e.getKey() + "' (shown once):",
                                                        NamedTextColor.GREEN)));
                        sender.sendMessage(Component.text("  " + e.getValue(), NamedTextColor.YELLOW));
                }
                for (final Token token : plugin.tokens().snapshotTokens()) {
                        sender.sendMessage(Component.text(
                                        "  " + token.name() + " token id: " + token.tokenId()
                                                        + (token.disabled() ? " (disabled)" : ""),
                                        NamedTextColor.GRAY)
                                        .append(Component.text("", NamedTextColor.WHITE)));
                }
                sender.sendMessage(Component.text("  endpoint: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                                config.mcp().bind() + ":" + config.mcp().port()
                                                                + config.mcp().endpoint() + " (tls="
                                                                + config.mcp().tls().mode() + ")",
                                                NamedTextColor.WHITE)));

                final String template = SetupSupport.clientConfigTemplate(config.mcp().port());
                try {
                        Files.writeString(plugin.dataDirectory().resolve("mcpServers.json"), template);
                        sender.sendMessage(PREFIX.append(
                                        Component.text("Client template written to plugins/MC2P/mcpServers.json",
                                                        NamedTextColor.GREEN)));
                } catch (final IOException ex) {
                        sender.sendMessage(PREFIX.append(
                                        Component.text("Could not write mcpServers.json: " + ex.getMessage(),
                                                        NamedTextColor.RED)));
                }
                sender.sendMessage(PREFIX
                                .append(Component.text("Agent mcpServers.json - replace <HOST> with your public host "
                                                + "and <TOKEN> with a token you create (or restrict) via /mc2p token:")));
                sender.sendMessage(Component.text(template, NamedTextColor.WHITE));
                sender.sendMessage(Component.text(
                                "Trust: with tls.mode=selfsigned, export plugins/MC2P/keystore.p12 "
                                                + "and pin it client-side; never insecureSkipVerify.",
                                NamedTextColor.GRAY));
        }

        private void status(final CommandSender sender) {
                final BackendConfig config = plugin.config();
                sender.sendMessage(PREFIX.append(Component.text("MC2P status")));
                sender.sendMessage(Component.text("  mode: ", NamedTextColor.GRAY)
                                .append(Component.text(plugin.effectiveMode(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  serverId: ", NamedTextColor.GRAY)
                                .append(Component.text(plugin.serverId(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  mcp endpoint: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                                config.mcp().bind() + ":" + config.mcp().port()
                                                                + config.mcp().endpoint() + " (tls="
                                                                + config.mcp().tls().mode() + ")",
                                                NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  restart strategy: ", NamedTextColor.GRAY)
                                .append(Component.text(config.restartStrategy(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  tools registered: ", NamedTextColor.GRAY)
                                .append(Component.text(plugin.registry().size(), NamedTextColor.WHITE)));
                for (final Token token : plugin.tokens().snapshotTokens()) {
                        sender.sendMessage(Component.text("  token " + token.name() + ": ", NamedTextColor.GRAY)
                                        .append(Component.text(
                                                        token.tokenId() + (token.disabled() ? " (disabled)" : ""),
                                                        NamedTextColor.WHITE)));
                }
                sender.sendMessage(Component.text("  audit log: ", NamedTextColor.GRAY)
                                .append(Component.text(config.audit().file(), NamedTextColor.WHITE)));
        }

        private void reload(final CommandSender sender) {
                try {
                        plugin.applyConfig();
                        sender.sendMessage(
                                        PREFIX.append(Component.text("Configuration reloaded.", NamedTextColor.GREEN)));
                } catch (final RuntimeException e) {
                        sender.sendMessage(PREFIX.append(
                                        Component.text("Reload failed: " + e.getMessage(), NamedTextColor.RED)));
                }
        }

        private void mode(final CommandSender sender, final String requested) {
                if (!"standalone".equals(requested) && !"backend".equals(requested)) {
                        sender.sendMessage(
                                        PREFIX.append(Component.text("Mode must be 'standalone' or 'backend'.",
                                                        NamedTextColor.RED)));
                        return;
                }
                if ("backend".equals(requested) && !ProxySecret.isPresent()) {
                        sender.sendMessage(PREFIX.append(Component.text(
                                        "Backend mode requires the proxy secret: set "
                                                        + plugin.config().proxy().secretEnv()
                                                        + " or plugins/MC2P/proxy-secret first.",
                                        NamedTextColor.RED)));
                        return;
                }
                try {
                        final Path active = ConfigFiles.switchTo(plugin, plugin.dataDirectory(), requested);
                        plugin.applyConfig();
                        sender.sendMessage(PREFIX.append(Component.text(
                                        "Switched to " + requested + " mode (" + active.getFileName() + ").",
                                        NamedTextColor.GREEN)));
                } catch (final IOException e) {
                        sender.sendMessage(
                                        PREFIX.append(Component.text("Mode switch failed: " + e.getMessage(),
                                                        NamedTextColor.RED)));
                }
        }

        private void activity(final CommandSender sender) {
                final java.util.List<ClientActivityTracker.Entry> active = plugin.activity().active();
                final int windowMinutes = plugin.config().auth().activityWindowMinutes();
                sender.sendMessage(PREFIX.append(Component.text("Active clients (last " + windowMinutes + " min):")));
                if (active.isEmpty()) {
                        sender.sendMessage(Component.text("  none", NamedTextColor.GRAY));
                        return;
                }
                for (final ClientActivityTracker.Entry e : active) {
                        sender.sendMessage(Component.text(
                                        "  " + e.name() + " " + e.remoteIp() + " requests=" + e.requestCount()
                                                        + " last="
                                                        + relativeTime(e.lastSeenMillis()),
                                        NamedTextColor.GRAY));
                }
        }

        private static String relativeTime(final long millis) {
                final long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
                if (seconds < 60) {
                        return seconds + "s ago";
                }
                return (seconds / 60) + "m " + (seconds % 60) + "s ago";
        }

        private void create(final CommandSender sender, final String name) {
                if (!TokenManager.isValidName(name)) {
                        sender.sendMessage(PREFIX.append(Component.text(
                                        "Invalid token name: " + name + " (use letters, digits, - and _, max 40)",
                                        NamedTextColor.RED)));
                        return;
                }
                final Token token = plugin.tokens().create(name);
                plugin.audit().log(null, "console", plugin.serverId(), "token", "create",
                                "{\"name\":\"" + name + "\"}");
                sender.sendMessage(
                                PREFIX.append(Component.text("New token '" + name + "', shown once:",
                                                NamedTextColor.GREEN)));
                sender.sendMessage(Component.text(token.tokenId(), NamedTextColor.YELLOW));
        }

        private void revoke(final CommandSender sender, final String name) {
                final boolean revoked = plugin.tokens().revoke(name);
                plugin.audit().log(null, "console", plugin.serverId(), "token", "revoke",
                                "{\"name\":\"" + name + "\"}");
                if (revoked) {
                        sender.sendMessage(PREFIX
                                        .append(Component.text("Token '" + name + "' revoked.", NamedTextColor.GREEN)));
                } else {
                        sender.sendMessage(
                                        PREFIX.append(Component.text("No token named '" + name + "' to revoke.",
                                                        NamedTextColor.YELLOW)));
                }
        }

        private void disable(final CommandSender sender, final String name) {
                final boolean changed = plugin.tokens().disable(name);
                plugin.audit().log(null, "console", plugin.serverId(), "token", "disable",
                                "{\"name\":\"" + name + "\"}");
                if (changed) {
                        sender.sendMessage(PREFIX.append(
                                        Component.text("Token '" + name + "' disabled.", NamedTextColor.GREEN)));
                } else {
                        sender.sendMessage(PREFIX.append(
                                        Component.text("No active token named '" + name + "' to disable.",
                                                        NamedTextColor.YELLOW)));
                }
        }

        private void enable(final CommandSender sender, final String name) {
                final boolean changed = plugin.tokens().enable(name);
                plugin.audit().log(null, "console", plugin.serverId(), "token", "enable",
                                "{\"name\":\"" + name + "\"}");
                if (changed) {
                        sender.sendMessage(PREFIX
                                        .append(Component.text("Token '" + name + "' enabled.", NamedTextColor.GREEN)));
                } else {
                        sender.sendMessage(PREFIX.append(
                                        Component.text("No disabled token named '" + name + "' to enable.",
                                                        NamedTextColor.YELLOW)));
                }
        }

        private void list(final CommandSender sender) {
                final List<Token> snapshot = plugin.tokens().snapshotTokens();
                if (snapshot.isEmpty()) {
                        sender.sendMessage(PREFIX.append(
                                        Component.text("No tokens configured. Run /mc2p token create <name>.")));
                        return;
                }
                sender.sendMessage(PREFIX.append(Component.text("Tokens:")));
                for (final Token token : snapshot) {
                        sender.sendMessage(Component.text(
                                        "  " + token.name() + " " + token.tokenId()
                                                        + (token.disabled() ? " (disabled)" : ""),
                                        NamedTextColor.GRAY));
                }
        }

        private void sendHelp(final CommandSender sender) {
                sender.sendMessage(PREFIX.append(Component.text("Usage:")));
                sender.sendMessage(Component.text("  /mc2p setup", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p status", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p reload", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p mode <standalone|backend>", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p activity", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p token create <name>", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p token revoke <name>", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p token disable <name>", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p token enable <name>", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  /mc2p token list", NamedTextColor.GRAY));
        }
}
