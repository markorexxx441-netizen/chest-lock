package id.chestlock;

import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class ChestLockPlugin extends JavaPlugin {

    private LockManager lockManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        lockManager = new LockManager(this);
        lockManager.loadSettings();
        lockManager.load();

        getServer().getPluginManager().registerEvents(new LockListener(this, lockManager), this);

        ChestLockCommand command = new ChestLockCommand(this, lockManager);
        PluginCommand pluginCommand = getCommand("chestlock");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        getLogger().info("ChestLock aktif. " + lockManager.getTotalLocks() + " kunci dimuat.");
    }

    @Override
    public void onDisable() {
        if (lockManager != null) {
            lockManager.save();
        }
    }

    public void reloadAll() {
        reloadConfig();
        lockManager.loadSettings();
    }

    public LockManager getLockManager() {
        return lockManager;
    }

    // ------------------------------------------------------------------
    // Pesan & suara
    // ------------------------------------------------------------------

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Kirim pesan dari config.yml (messages.<key>). Bisa berupa teks satu baris atau list.
     * Placeholder diberikan berpasangan: "owner", "Steve", "limit", "1", ...
     */
    public void send(CommandSender target, String key, String... replacements) {
        String path = "messages." + key;
        List<String> lines = new ArrayList<>();
        if (getConfig().isList(path)) {
            lines.addAll(getConfig().getStringList(path));
        } else {
            lines.add(getConfig().getString(path, "&cPesan '" + key + "' tidak ada di config.yml"));
        }

        String prefix = getConfig().getString("messages.prefix", "");
        for (int i = 0; i < lines.size(); i++) {
            String line = replace(lines.get(i), replacements);
            target.sendMessage(color((i == 0 ? prefix : "") + line));
        }
    }

    public String replace(String text, String... pairs) {
        text = text.replace("{discord}", getConfig().getString("discord-link", ""));
        text = text.replace("{rank}", getConfig().getString("upgrade-rank-name", "Admin"));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return text;
    }

    public void play(Player player, Sound sound) {
        if (getConfig().getBoolean("sounds", true)) {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
    }
}
