package dev.server.dashcompass;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class DashCompass extends JavaPlugin implements Listener, CommandExecutor {

    private NamespacedKey compassKey;
    private final Map<UUID, Long> dashCooldowns = new HashMap<>();
    private final Map<UUID, Long> teleportCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        compassKey = new NamespacedKey(this, "dash_compass");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("dashcompass")).setExecutor(this);
    }

    // ---------------------------------------------------------------- item

    private ItemStack createCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Dash Compass", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click: dash through the player you're looking at",
                        NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Shift + right-click: teleport to a nearby player",
                        NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isDashCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("Specify a player.");
            return true;
        }
        target.getInventory().addItem(createCompass())
                .values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Gave the Dash Compass to " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    // ---------------------------------------------------------------- use

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isDashCompass(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!p.hasPermission("dashcompass.use")) return;

        if (p.isSneaking()) openTeleportMenu(p);
        else dash(p);
    }

    // ---------------------------------------------------------------- dash

    private void dash(Player p) {
        if (onCooldown(p, dashCooldowns, "Dash")) return;

        double range = getConfig().getDouble("dash-range", 25.0);
        Location eye = p.getEyeLocation();

        // Ray stops at the first block OR player, so you can't dash through walls.
        RayTraceResult hit = p.getWorld().rayTrace(eye, eye.getDirection(), range,
                FluidCollisionMode.NEVER, true, 0.4,
                en -> en instanceof Player t && !t.equals(p) && t.getGameMode() != GameMode.SPECTATOR);

        if (hit == null || !(hit.getHitEntity() instanceof Player target)) {
            p.sendActionBar(Component.text("Look at a player to dash.", NamedTextColor.RED));
            return;
        }

        Location start = p.getLocation();
        Location tLoc = target.getLocation();
        Vector forward = tLoc.toVector().subtract(start.toVector()).setY(0);
        if (forward.lengthSquared() < 1.0E-6) forward = eye.getDirection().setY(0);
        forward.normalize();

        double overshoot = getConfig().getDouble("dash-overshoot", 2.0);
        Location dest = tLoc.clone().add(forward.clone().multiply(overshoot));
        dest.setYaw(start.getYaw());
        dest.setPitch(start.getPitch());

        // Make sure we don't land inside blocks; otherwise just land on the target's spot.
        boolean pathClear = tLoc.getWorld().rayTraceBlocks(tLoc.clone().add(0, 1, 0), forward,
                overshoot, FluidCollisionMode.NEVER, true) == null;
        if (!pathClear || !dest.getBlock().isPassable()
                || !dest.clone().add(0, 1, 0).getBlock().isPassable()) {
            dest = tLoc.clone();
            dest.setYaw(start.getYaw());
            dest.setPitch(start.getPitch());
        }

        // Trail effect
        Vector line = dest.toVector().subtract(start.toVector());
        double len = line.length();
        Vector step = line.clone().normalize().multiply(0.5);
        Location cursor = start.clone().add(0, 1, 0);
        for (double d = 0; d < len; d += 0.5) {
            start.getWorld().spawnParticle(Particle.SWEEP_ATTACK, cursor, 1, 0, 0, 0, 0);
            start.getWorld().spawnParticle(Particle.END_ROD, cursor, 2, 0.1, 0.1, 0.1, 0.01);
            cursor.add(step);
        }

        p.teleport(dest);
        p.setFallDistance(0f);
        p.getWorld().playSound(dest, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);

        // MAGIC damage type is tagged "bypasses_armor", so armor never reduces this hit.
        double damage = getConfig().getDouble("dash-damage", 10.0);
        DamageSource source = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(p)
                .withDirectEntity(p)
                .build();
        target.damage(damage, source);

        setCooldown(p, dashCooldowns, getConfig().getInt("dash-cooldown-seconds", 3));
    }

    // ---------------------------------------------------------------- teleport menu

    private static final class TeleportMenu implements InventoryHolder {
        final Map<Integer, UUID> slots = new HashMap<>();
        Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private void openTeleportMenu(Player p) {
        if (onCooldownCheckOnly(p, teleportCooldowns, "Teleport")) return;

        double radius = getConfig().getDouble("teleport-radius", 400.0);
        double radiusSq = radius * radius;

        List<Player> nearby = new ArrayList<>();
        for (Player other : p.getWorld().getPlayers()) {
            if (other.equals(p) || other.getGameMode() == GameMode.SPECTATOR) continue;
            if (other.getLocation().distanceSquared(p.getLocation()) <= radiusSq) nearby.add(other);
        }
        if (nearby.isEmpty()) {
            p.sendActionBar(Component.text("No players within " + (int) radius + " blocks.", NamedTextColor.RED));
            return;
        }
        nearby.sort(Comparator.comparingDouble(o -> o.getLocation().distanceSquared(p.getLocation())));

        TeleportMenu holder = new TeleportMenu();
        int size = Math.min(54, ((Math.min(nearby.size(), 54) - 1) / 9 + 1) * 9);
        Inventory inv = Bukkit.createInventory(holder, size,
                Component.text("Teleport (" + (int) radius + " block radius)"));
        holder.inv = inv;

        for (int i = 0; i < Math.min(nearby.size(), 54); i++) {
            Player t = nearby.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(t);
            meta.displayName(Component.text(t.getName(), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            int dist = (int) t.getLocation().distance(p.getLocation());
            meta.lore(List.of(Component.text(dist + " blocks away", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            head.setItemMeta(meta);
            inv.setItem(i, head);
            holder.slots.put(i, t.getUniqueId());
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof TeleportMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        UUID id = menu.slots.get(e.getSlot());
        if (id == null) return;
        Player target = Bukkit.getPlayer(id);
        p.closeInventory();

        if (target == null || !target.isOnline() || !target.getWorld().equals(p.getWorld())) {
            p.sendActionBar(Component.text("That player is no longer available.", NamedTextColor.RED));
            return;
        }
        double radius = getConfig().getDouble("teleport-radius", 400.0);
        if (target.getLocation().distanceSquared(p.getLocation()) > radius * radius) {
            p.sendActionBar(Component.text("That player moved out of range.", NamedTextColor.RED));
            return;
        }
        if (onCooldown(p, teleportCooldowns, "Teleport")) return;

        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
        p.teleport(target.getLocation());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
        setCooldown(p, teleportCooldowns, getConfig().getInt("teleport-cooldown-seconds", 30));
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof TeleportMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        dashCooldowns.remove(e.getPlayer().getUniqueId());
        teleportCooldowns.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- cooldowns

    private boolean onCooldown(Player p, Map<UUID, Long> map, String name) {
        return onCooldownCheckOnly(p, map, name);
    }

    private boolean onCooldownCheckOnly(Player p, Map<UUID, Long> map, String name) {
        long now = System.currentTimeMillis();
        long until = map.getOrDefault(p.getUniqueId(), 0L);
        if (now < until) {
            double left = (until - now) / 1000.0;
            p.sendActionBar(Component.text(String.format("%s on cooldown: %.1fs", name, left), NamedTextColor.RED));
            return true;
        }
        return false;
    }

    private void setCooldown(Player p, Map<UUID, Long> map, int seconds) {
        map.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }
}
