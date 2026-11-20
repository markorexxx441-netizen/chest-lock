package id.chestlock;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Menyimpan semua kunci, mengecek akses, dan menghitung limit per player.
 */
public final class LockManager {

    private static final String LIMIT_PERMISSION_PREFIX = "chestlock.limit.";

    /** Satu kunci = satu sign + satu atau dua block chest (chest ganda). */
    public static final class Lock {
        private final UUID owner;
        private final String ownerName;
        private final String signKey;
        private final Set<String> blockKeys = new LinkedHashSet<>();
        private final long created;

        Lock(UUID owner, String ownerName, String signKey, long created) {
            this.owner = owner;
            this.ownerName = ownerName;
            this.signKey = signKey;
            this.created = created;
        }

        public UUID getOwner() {
            return owner;
        }

        public String getOwnerName() {
            return ownerName;
        }

        public String getSignKey() {
            return signKey;
        }

        public Set<String> getBlockKeys() {
            return Collections.unmodifiableSet(blockKeys);
        }

        public long getCreated() {
            return created;
        }
    }

    private final ChestLockPlugin plugin;
    private final File file;

    private final Map<String, Lock> bySign = new HashMap<>();
    private final Map<String, Lock> byBlock = new HashMap<>();

    // Pengaturan (dibaca dari config.yml)
    private int defaultLimit = 1;
    private boolean protectHoppers = true;
    private boolean protectExplosions = true;
    private boolean waxSigns = true;
    private final Set<Material> lockable = EnumSet.noneOf(Material.class);
    private final List<String> keywords = new ArrayList<>();
    private final List<String> signTemplate = new ArrayList<>();

    public LockManager(ChestLockPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "locks.yml");
    }

    // ------------------------------------------------------------------
    // Pengaturan
    // ------------------------------------------------------------------

    public void loadSettings() {
        FileConfiguration config = plugin.getConfig();

        defaultLimit = config.getInt("limits.default", 1);
        protectHoppers = config.getBoolean("protection.hoppers", true);
        protectExplosions = config.getBoolean("protection.explosions", true);
        waxSigns = config.getBoolean("sign.wax", true);

        lockable.clear();
        for (String name : config.getStringList("lockable-blocks")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                lockable.add(material);
            } else {
                plugin.getLogger().warning("lockable-blocks: material tidak dikenal '" + name + "'");
            }
        }
        if (lockable.isEmpty()) {
            lockable.add(Material.CHEST);
            lockable.add(Material.TRAPPED_CHEST);
            lockable.add(Material.BARREL);
        }

        keywords.clear();
        for (String keyword : config.getStringList("sign.keywords")) {
            keywords.add(keyword.trim().toLowerCase(Locale.ROOT));
        }
        if (keywords.isEmpty()) {
            keywords.add("[kunci]");
        }

        signTemplate.clear();
        signTemplate.addAll(config.getStringList("sign.locked-lines"));
    }

    public boolean isProtectHoppers() {
        return protectHoppers;
    }

    public boolean isProtectExplosions() {
        return protectExplosions;
    }

    public boolean isWaxSigns() {
        return waxSigns;
    }

    public boolean isLockable(Material material) {
        return lockable.contains(material);
    }

    public boolean isKeyword(String text) {
        if (text == null) {
            return false;
        }
        return keywords.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    /** Isi 4 baris sign setelah terkunci. */
    public List<String> signLines(String playerName) {
        List<String> lines = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            String raw = i < signTemplate.size() ? signTemplate.get(i) : "";
            lines.add(ChatColor.translateAlternateColorCodes('&', raw.replace("{player}", playerName)));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Key lokasi
    // ------------------------------------------------------------------

    public static String key(Block block) {
        return block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    /** Ubah key kembali jadi block. Mengembalikan null kalau world tidak ditemukan. */
    public Block blockFromKey(String key) {
        String[] parts = key.split(",");
        int n = parts.length;
        if (n < 4) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[n - 3]);
            int y = Integer.parseInt(parts[n - 2]);
            int z = Integer.parseInt(parts[n - 1]);
            String worldName = String.join(",", Arrays.copyOfRange(parts, 0, n - 3));
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : world.getBlockAt(x, y, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Format enak dibaca: "world (10, 64, -20)". */
    public static String pretty(String key) {
        String[] parts = key.split(",");
        int n = parts.length;
        if (n < 4) {
            return key;
        }
        String world = String.join(",", Arrays.copyOfRange(parts, 0, n - 3));
        return world + " (" + parts[n - 3] + ", " + parts[n - 2] + ", " + parts[n - 1] + ")";
    }

    // ------------------------------------------------------------------
    // Pencarian kunci
    // ------------------------------------------------------------------

    /** Kunci yang tepat berada di block ini (block chest ATAU block sign). */
    public Lock getLockAt(Block block) {
        String key = key(block);
        Lock lock = byBlock.get(key);
        return lock != null ? lock : bySign.get(key);
    }

    public Lock getLockBySign(Block block) {
        return bySign.get(key(block));
    }

    /** Sama seperti getLockAt, tapi untuk chest ganda juga mengecek separuh satunya. */
    public Lock findLock(Block block) {
        Lock lock = getLockAt(block);
        if (lock != null) {
            return lock;
        }
        if (isChestType(block.getType())) {
            for (Block part : getContainerBlocks(block)) {
                lock = byBlock.get(key(part));
                if (lock != null) {
                    return lock;
                }
            }
        }
        return null;
    }

    public Lock getLockOfHolder(InventoryHolder holder) {
        if (holder == null) {
            return null;
        }
        if (holder instanceof DoubleChest doubleChest) {
            Lock lock = getLockOfHolder(doubleChest.getLeftSide());
            return lock != null ? lock : getLockOfHolder(doubleChest.getRightSide());
        }
        if (holder instanceof BlockState state) {
            return byBlock.get(key(state.getBlock()));
        }
        return null;
    }

    public boolean isProtected(Block block) {
        String key = key(block);
        return byBlock.containsKey(key) || bySign.containsKey(key);
    }

    public boolean canAccess(Player player, Lock lock) {
        return lock.owner.equals(player.getUniqueId()) || player.hasPermission("chestlock.bypass");
    }

    private boolean isChestType(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }

    /** Block yang membentuk container ini: 1 block, atau 2 block kalau chest ganda. */
    public List<Block> getContainerBlocks(Block block) {
        List<Block> result = new ArrayList<>(2);
        BlockState state = block.getState();
        if (state instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest doubleChest) {
                addHolderBlock(result, doubleChest.getLeftSide());
                addHolderBlock(result, doubleChest.getRightSide());
            }
        }
        if (!result.contains(block)) {
            result.add(block);
        }
        return result;
    }

    private void addHolderBlock(List<Block> list, InventoryHolder holder) {
        if (holder instanceof BlockState state) {
            Block block = state.getBlock();
            if (!list.contains(block)) {
                list.add(block);
            }
        }
    }

    // ------------------------------------------------------------------
    // Limit
    // ------------------------------------------------------------------

    /** Batas jumlah kunci untuk player ini. -1 berarti tanpa batas. */
    public int getLimit(Player player) {
        if (player.hasPermission("chestlock.unlimited")) {
            return -1;
        }
        int limit = defaultLimit;
        if (limit < 0) {
            return -1;
        }
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String permission = info.getPermission().toLowerCase(Locale.ROOT);
            if (permission.startsWith(LIMIT_PERMISSION_PREFIX)) {
                try {
                    int value = Integer.parseInt(permission.substring(LIMIT_PERMISSION_PREFIX.length()));
                    limit = Math.max(limit, value);
                } catch (NumberFormatException ignored) {
                    // permission salah ketik, abaikan
                }
            }
        }
        return limit;
    }

    public int countLocks(UUID owner) {
        int count = 0;
        for (Lock lock : bySign.values()) {
            if (lock.owner.equals(owner)) {
                count++;
            }
        }
        return count;
    }

    public List<Lock> getLocks(UUID owner) {
        List<Lock> result = new ArrayList<>();
        for (Lock lock : bySign.values()) {
            if (lock.owner.equals(owner)) {
                result.add(lock);
            }
        }
        return result;
    }

    public List<Lock> getLocksByOwnerName(String name) {
        List<Lock> result = new ArrayList<>();
        for (Lock lock : bySign.values()) {
            if (lock.ownerName.equalsIgnoreCase(name)) {
                result.add(lock);
            }
        }
        return result;
    }

    public int getTotalLocks() {
        return bySign.size();
    }

    // ------------------------------------------------------------------
    // Buat / ubah / hapus kunci
    // ------------------------------------------------------------------

    public Lock createLock(Player owner, Block signBlock, Collection<Block> containerBlocks) {
        Lock lock = new Lock(owner.getUniqueId(), owner.getName(), key(signBlock), System.currentTimeMillis());
        for (Block block : containerBlocks) {
            lock.blockKeys.add(key(block));
        }
        index(lock);
        save();
        return lock;
    }

    /** Dipanggil saat pemilik menaruh chest di sebelah chest terkunci miliknya sehingga jadi chest ganda. */
    public void extendLock(Lock lock, Block placed) {
        if (bySign.get(lock.signKey) != lock) {
            return; // kunci sudah dihapus
        }
        List<Block> container = getContainerBlocks(placed);
        boolean linked = false;
        for (Block block : container) {
            if (lock.blockKeys.contains(key(block))) {
                linked = true;
                break;
            }
        }
        if (!linked) {
            return;
        }
        boolean changed = false;
        for (Block block : container) {
            String key = key(block);
            if (!byBlock.containsKey(key)) {
                lock.blockKeys.add(key);
                byBlock.put(key, lock);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    public void removeLock(Lock lock) {
        unindex(lock);
        save();
    }

    private void index(Lock lock) {
        bySign.put(lock.signKey, lock);
        for (String key : lock.blockKeys) {
            byBlock.put(key, lock);
        }
    }

    private void unindex(Lock lock) {
        bySign.remove(lock.signKey, lock);
        for (String key : lock.blockKeys) {
            byBlock.remove(key, lock);
        }
    }

    /** Kosongkan tulisan sign yang sudah tidak jadi sign kunci. */
    public void clearSign(String signKey) {
        if (bySign.containsKey(signKey)) {
            return; // masih dipakai kunci lain
        }
        Block block = blockFromKey(signKey);
        if (block == null || !isChunkLoaded(block)) {
            return;
        }
        BlockState state = block.getState();
        if (state instanceof Sign sign) {
            for (Side side : Side.values()) {
                SignSide signSide = sign.getSide(side);
                for (int i = 0; i < 4; i++) {
                    signSide.setLine(i, "");
                }
            }
            sign.setWaxed(false);
            sign.update();
        }
    }

    /**
     * Hapus kunci milik player yang sign atau chest-nya sudah hilang (misalnya dihapus lewat
     * WorldEdit atau plugin lain), supaya tidak memakan slot limit selamanya.
     */
    public int purgeInvalid(UUID owner) {
        List<Lock> dead = new ArrayList<>();
        for (Lock lock : bySign.values()) {
            if (lock.owner.equals(owner) && !isStillValid(lock)) {
                dead.add(lock);
            }
        }
        for (Lock lock : dead) {
            unindex(lock);
        }
        if (!dead.isEmpty()) {
            save();
        }
        return dead.size();
    }

    private boolean isStillValid(Lock lock) {
        Block sign = blockFromKey(lock.signKey);
        if (sign == null || !isChunkLoaded(sign)) {
            return true; // world/chunk tidak dimuat, anggap masih valid
        }
        if (!(sign.getState() instanceof Sign)) {
            return false;
        }
        for (String key : lock.blockKeys) {
            Block block = blockFromKey(key);
            if (block == null || !isChunkLoaded(block) || isLockable(block.getType())) {
                return true;
            }
        }
        return false;
    }

    private boolean isChunkLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    // ------------------------------------------------------------------
    // Simpan & muat (locks.yml)
    // ------------------------------------------------------------------

    public void load() {
        bySign.clear();
        byBlock.clear();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("locks");
        if (section == null) {
            return;
        }

        int skipped = 0;
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                skipped++;
                continue;
            }
            String ownerRaw = entry.getString("owner");
            String signKey = entry.getString("sign");
            if (ownerRaw == null || signKey == null) {
                skipped++;
                continue;
            }
            UUID owner;
            try {
                owner = UUID.fromString(ownerRaw);
            } catch (IllegalArgumentException ex) {
                skipped++;
                continue;
            }
            Lock lock = new Lock(owner, entry.getString("name", "???"), signKey, entry.getLong("created", 0L));
            lock.blockKeys.addAll(entry.getStringList("blocks"));
            if (lock.blockKeys.isEmpty()) {
                skipped++;
                continue;
            }
            index(lock);
        }

        if (skipped > 0) {
            plugin.getLogger().warning(skipped + " entri di locks.yml rusak dan dilewati.");
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        int i = 0;
        for (Lock lock : bySign.values()) {
            String path = "locks." + i++;
            yaml.set(path + ".owner", lock.owner.toString());
            yaml.set(path + ".name", lock.ownerName);
            yaml.set(path + ".sign", lock.signKey);
            yaml.set(path + ".blocks", new ArrayList<>(lock.blockKeys));
            yaml.set(path + ".created", lock.created);
        }

        File tmp = new File(plugin.getDataFolder(), "locks.yml.tmp");
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(tmp);
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Gagal menyimpan locks.yml", ex);
        }
    }
}
