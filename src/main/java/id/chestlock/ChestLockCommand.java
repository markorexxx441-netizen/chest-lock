package id.chestlock;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ChestLockCommand implements CommandExecutor, TabCompleter {

    private static final int LOOK_DISTANCE = 6;

    private final ChestLockPlugin plugin;
    private final LockManager manager;

    public ChestLockCommand(ChestLockPlugin plugin, LockManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(sender);
            case "list" -> list(sender, args);
            case "unlock" -> unlock(sender);
            case "reload" -> reload(sender);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        plugin.send(sender, "help");
        if (sender.hasPermission("chestlock.admin")) {
            plugin.send(sender, "help-admin");
        }
    }

    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "players-only");
            return;
        }
        LockManager.Lock lock = lookAtLock(player);
        if (lock == null) {
            plugin.send(player, "look-at-lock");
            return;
        }
        String date = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.forLanguageTag("id-ID"))
                .format(new Date(lock.getCreated()));
        plugin.send(player, "info", "owner", lock.getOwnerName(), "date", date);
    }

    private void list(CommandSender sender, String[] args) {
        List<LockManager.Lock> locks;
        String targetName;
        String limitText = "-";

        if (args.length >= 2) {
            if (!sender.hasPermission("chestlock.admin")) {
                plugin.send(sender, "no-permission");
                return;
            }
            targetName = args[1];
            locks = manager.getLocksByOwnerName(targetName);
        } else {
            if (!(sender instanceof Player player)) {
                plugin.send(sender, "players-only");
                return;
            }
            targetName = player.getName();
            locks = manager.getLocks(player.getUniqueId());
            int limit = manager.getLimit(player);
            limitText = limit < 0 ? "Tak terbatas" : String.valueOf(limit);
        }

        if (locks.isEmpty()) {
            plugin.send(sender, "list-empty", "player", targetName);
            return;
        }

        plugin.send(sender, "list-header",
                "player", targetName,
                "used", String.valueOf(locks.size()),
                "limit", limitText);
        for (LockManager.Lock lock : locks) {
            plugin.send(sender, "list-entry", "location", LockManager.pretty(lock.getSignKey()));
        }
    }

    private void unlock(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "players-only");
            return;
        }
        LockManager.Lock lock = lookAtLock(player);
        if (lock == null) {
            plugin.send(player, "look-at-lock");
            return;
        }

        boolean isOwner = lock.getOwner().equals(player.getUniqueId());
        if (!isOwner && !player.hasPermission("chestlock.admin")) {
            plugin.send(player, "access-denied", "owner", lock.getOwnerName());
            plugin.play(player, Sound.BLOCK_CHEST_LOCKED);
            return;
        }

        manager.removeLock(lock);
        manager.clearSign(lock.getSignKey());

        if (!isOwner) {
            plugin.getLogger().info(player.getName() + " membuka paksa kunci milik "
                    + lock.getOwnerName() + " di " + LockManager.pretty(lock.getSignKey()));
        }
        plugin.send(player, "unlocked");
        plugin.play(player, Sound.BLOCK_IRON_DOOR_OPEN);
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("chestlock.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        plugin.reloadAll();
        plugin.send(sender, "reloaded");
    }

    private LockManager.Lock lookAtLock(Player player) {
        Block target = player.getTargetBlockExact(LOOK_DISTANCE);
        if (target == null) {
            return null;
        }
        return manager.findLock(target);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        boolean admin = sender.hasPermission("chestlock.admin");

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("help", "info", "list", "unlock"));
            if (admin) {
                subs.add("reload");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : subs) {
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
        } else if (args.length == 2 && admin && args[0].equalsIgnoreCase("list")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
        }
        return result;
    }
}
