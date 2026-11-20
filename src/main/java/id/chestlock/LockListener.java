package id.chestlock;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LockListener implements Listener {

    private static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final long NOTICE_COOLDOWN_MS = 800L;

    private final ChestLockPlugin plugin;
    private final LockManager manager;
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    public LockListener(ChestLockPlugin plugin, LockManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ==================================================================
    // 1. Membuat kunci lewat sign [KUNCI]
    // ==================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Block signBlock = event.getBlock();

        // Sign kunci yang sudah ada tidak boleh diedit siapa pun.
        if (manager.getLockBySign(signBlock) != null) {
            event.setCancelled(true);
            plugin.send(player, "sign-protected");
            return;
        }

        String firstLine = ChatColor.stripColor(event.getLine(0));
        if (!manager.isKeyword(firstLine)) {
            return; // sign biasa
        }

        if (!player.hasPermission("chestlock.use")) {
            event.setCancelled(true);
            plugin.send(player, "no-permission");
            return;
        }

        Block attached = getAttachedBlock(signBlock);
        if (attached == null || !manager.isLockable(attached.getType())) {
            event.setCancelled(true);
            plugin.send(player, "must-attach");
            plugin.play(player, Sound.BLOCK_CHEST_LOCKED);
            return;
        }

        List<Block> container = manager.getContainerBlocks(attached);

        // Chest (atau separuh chest ganda) sudah dikunci?
        for (Block block : container) {
            LockManager.Lock existing = manager.getLockAt(block);
            if (existing != null) {
                event.setCancelled(true);
                if (existing.getOwner().equals(player.getUniqueId())) {
                    plugin.send(player, "already-yours");
                } else {
                    plugin.send(player, "already-locked-other", "owner", existing.getOwnerName());
                }
                plugin.play(player, Sound.BLOCK_CHEST_LOCKED);
                return;
            }
        }

        // Cek limit rank.
        manager.purgeInvalid(player.getUniqueId());
        int limit = manager.getLimit(player);
        int used = manager.countLocks(player.getUniqueId());
        if (limit >= 0 && used >= limit) {
            event.setCancelled(true);
            plugin.send(player, "limit-reached",
                    "limit", String.valueOf(limit),
                    "used", String.valueOf(used));
            plugin.play(player, Sound.ENTITY_VILLAGER_NO);
            return;
        }

        // Kunci!
        manager.createLock(player, signBlock, container);

        List<String> lines = manager.signLines(player.getName());
        for (int i = 0; i < 4; i++) {
            event.setLine(i, lines.get(i));
        }
        if (manager.isWaxSigns()) {
            waxLater(signBlock);
        }

        plugin.send(player, "locked",
                "used", String.valueOf(used + 1),
                "limit", limitText(limit));
        plugin.play(player, Sound.BLOCK_IRON_DOOR_CLOSE);
    }

    /** Block tempat sign menempel: di belakang wall sign, atau di bawah standing sign. */
    private Block getAttachedBlock(Block signBlock) {
        Material type = signBlock.getType();
        if (Tag.WALL_SIGNS.isTagged(type) && signBlock.getBlockData() instanceof Directional directional) {
            return signBlock.getRelative(directional.getFacing().getOppositeFace());
        }
        if (Tag.STANDING_SIGNS.isTagged(type)) {
            return signBlock.getRelative(BlockFace.DOWN);
        }
        return null;
    }

    private void waxLater(Block signBlock) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            BlockState state = signBlock.getState();
            if (state instanceof Sign sign) {
                sign.setWaxed(true);
                sign.update();
            }
        });
    }

    private String limitText(int limit) {
        return limit < 0 ? "Tak terbatas" : String.valueOf(limit);
    }

    // ==================================================================
    // 2. Akses chest (buka)
    // ==================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !manager.isLockable(block.getType())) {
            return;
        }
        LockManager.Lock lock = manager.findLock(block);
        if (lock == null) {
            return;
        }

        Player player = event.getPlayer();
        if (manager.canAccess(player, lock)) {
            notifyBypass(player, lock);
            return;
        }
        event.setCancelled(true);
        deny(player, "access-denied", lock);
    }

    /** Jaring pengaman: chest tidak bisa dibuka lewat cara apa pun oleh orang lain. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        LockManager.Lock lock = manager.getLockOfHolder(event.getInventory().getHolder());
        if (lock == null || manager.canAccess(player, lock)) {
            return;
        }
        event.setCancelled(true);
        deny(player, "access-denied", lock);
    }

    // ==================================================================
    // 3. Menghancurkan chest / sign
    // ==================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        LockManager.Lock lock = manager.getLockAt(event.getBlock());
        if (lock == null) {
            return;
        }
        Player player = event.getPlayer();
        if (manager.canAccess(player, lock)) {
            return;
        }
        event.setCancelled(true);
        deny(player, "break-denied", lock);
    }

    /** Kunci baru dihapus kalau break benar-benar terjadi (tidak dibatalkan plugin lain). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakMonitor(BlockBreakEvent event) {
        LockManager.Lock lock = manager.getLockAt(event.getBlock());
        if (lock == null) {
            return;
        }
        Player player = event.getPlayer();
        String signKey = lock.getSignKey();

        manager.removeLock(lock);
        // Kalau yang hancur chest dan sign masih menempel di separuh chest ganda, kosongkan tulisannya.
        Bukkit.getScheduler().runTask(plugin, () -> manager.clearSign(signKey));

        plugin.send(player, "unlocked-by-break");
        plugin.play(player, Sound.BLOCK_IRON_DOOR_OPEN);
    }

    // ==================================================================
    // 4. Menaruh chest di sebelah chest terkunci
    // ==================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();

        // Ada data kunci di posisi yang baru diisi block = data basi (block lama dihapus
        // lewat WorldEdit/plugin lain). Bersihkan.
        LockManager.Lock stale = manager.getLockAt(placed);
        if (stale != null) {
            manager.removeLock(stale);
        }

        Material type = placed.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            return;
        }

        Player player = event.getPlayer();
        LockManager.Lock ownLock = null;
        for (BlockFace face : HORIZONTAL) {
            Block neighbor = placed.getRelative(face);
            if (neighbor.getType() != type) {
                continue;
            }
            LockManager.Lock lock = manager.getLockAt(neighbor);
            if (lock == null) {
                continue;
            }
            if (!manager.canAccess(player, lock)) {
                event.setCancelled(true);
                deny(player, "place-denied", lock);
                return;
            }
            if (lock.getOwner().equals(player.getUniqueId())) {
                ownLock = lock;
            }
        }

        if (ownLock != null) {
            // Pemilik membuat chest ganda: separuh baru ikut terkunci.
            LockManager.Lock lock = ownLock;
            Bukkit.getScheduler().runTask(plugin, () -> manager.extendLock(lock, placed));
        }
    }

    // ==================================================================
    // 5. Proteksi tambahan
    // ==================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (manager.isProtectExplosions()) {
            event.blockList().removeIf(manager::isProtected);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (manager.isProtectExplosions()) {
            event.blockList().removeIf(manager::isProtected);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        // Wither, enderman, dll.
        if (manager.isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        // Piston bisa menghancurkan sign yang ada tepat di depan barisan block yang didorong.
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        int reach = event.getBlocks().size() + 1;
        for (int i = 1; i <= reach; i++) {
            if (manager.isProtected(piston.getRelative(direction, i))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (manager.isProtected(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (!manager.isProtectHoppers()) {
            return;
        }
        if (manager.getLockOfHolder(event.getSource().getHolder()) != null
                || manager.getLockOfHolder(event.getDestination().getHolder()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    // ==================================================================
    // Util
    // ==================================================================

    private void deny(Player player, String messageKey, LockManager.Lock lock) {
        if (throttled(player)) {
            return;
        }
        plugin.send(player, messageKey, "owner", lock.getOwnerName());
        plugin.play(player, Sound.BLOCK_CHEST_LOCKED);
    }

    private void notifyBypass(Player player, LockManager.Lock lock) {
        if (lock.getOwner().equals(player.getUniqueId()) || throttled(player)) {
            return;
        }
        plugin.send(player, "bypass", "owner", lock.getOwnerName());
    }

    /** Cegah pesan yang sama muncul berkali-kali dalam sekejap (event interact terpanggil 2x). */
    private boolean throttled(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN_MS) {
            return true;
        }
        lastNotice.put(player.getUniqueId(), now);
        return false;
    }
}
