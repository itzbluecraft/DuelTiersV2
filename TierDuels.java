package dev.tierduels;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class TierDuels extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private StatsManager stats;
    private DuelManager duels;
    private NamespacedKey wandKey;
    private QueueGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        stats = new StatsManager(this);
        stats.load();
        duels = new DuelManager(this);
        duels.load();
        wandKey = new NamespacedKey(this, "wand");
        gui = new QueueGui(this, duels);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(duels, this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        Objects.requireNonNull(getCommand("duel")).setExecutor(this);
        Objects.requireNonNull(getCommand("tier")).setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, duels::tick, 20L, 20L);

        for (Player p : Bukkit.getOnlinePlayers()) updateTab(p);
        getLogger().info("TierDuels v" + getPluginMeta().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        if (duels != null) duels.shutdown();
        if (stats != null) stats.save();
    }

    StatsManager stats() {
        return stats;
    }

    /** Tier a player has before winning anything: None (default) or LT5. */
    Tier startTier() {
        Tier t = Tier.parse(getConfig().getString("start-tier", "None"));
        return t == null ? Tier.NONE : t;
    }

    int winsPerTier() {
        return Math.max(1, getConfig().getInt("wins-per-tier", 3));
    }

    // -------------------------------------------------------------- messages

    /** A message without the prefix. */
    Component text(String key, String... replace) {
        String t = getConfig().getString("messages." + key, "&cMissing message: " + key);
        for (int i = 0; i + 1 < replace.length; i += 2) {
            t = t.replace(replace[i], replace[i + 1]);
        }
        return LEGACY.deserialize(t);
    }

    /** A message with the prefix. */
    Component msg(String key, String... replace) {
        return LEGACY.deserialize(getConfig().getString("prefix", "")).append(text(key, replace));
    }

    void send(CommandSender s, String key, String... replace) {
        s.sendMessage(msg(key, replace));
    }

    // ------------------------------------------------------- tier display

    private static Component tierTag(Tier t) {
        return Component.text("[" + t.label + "]", t.color);
    }

    void refreshTab(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) updateTab(p);
    }

    private void updateTab(Player p) {
        if (!getConfig().getBoolean("tab-prefix", true)) return;
        PlayerStats s = stats.get(p.getUniqueId(), p.getName());
        if (s.tier == Tier.NONE) {
            p.playerListName(null);
            return;
        }
        p.playerListName(tierTag(s.tier)
                .append(Component.text(" "))
                .append(Component.text(p.getName(), NamedTextColor.WHITE)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        stats.get(p.getUniqueId(), p.getName());

        // A promotion that was still waiting when the player left or the server stopped
        Tier promoted = stats.promoteIfReady(p.getUniqueId());
        if (promoted != null) send(p, "promoted", "%tier%", promoted.colored());

        updateTab(p);
        duels.onJoin(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("chat-prefix", true)) return;
        Player p = e.getPlayer();
        Tier tier = stats.get(p.getUniqueId(), p.getName()).tier;
        if (tier == Tier.NONE) return; // unranked players keep the default chat format
        Component tag = tierTag(tier);
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) ->
                tag.append(Component.text(" "))
                        .append(name)
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(message)));
    }

    // -------------------------------------------------------------- commands

    // ------------------------------------------------------------------ wand

    private ItemStack createWand() {
        Material m = Material.matchMaterial(getConfig().getString("wand-material", "WOODEN_AXE"));
        if (m == null || m.isAir()) m = Material.WOODEN_AXE;

        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("wand-name").decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                text("wand-lore-1").decoration(TextDecoration.ITALIC, false),
                text("wand-lore-2").decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /** Left click = position 1, right click = position 2. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onWand(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action action = e.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isWand(e.getItem())) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("tierduels.admin")) return;

        e.setCancelled(true);
        Block b = e.getClickedBlock();
        if (b == null) return;

        int index = action == Action.LEFT_CLICK_BLOCK ? 1 : 2;
        duels.setSelection(p.getUniqueId(), index, b.getLocation());
        send(p, "wand-pos", "%pos%", String.valueOf(index),
                "%x%", String.valueOf(b.getX()), "%y%", String.valueOf(b.getY()), "%z%", String.valueOf(b.getZ()));

        Location[] sel = duels.selection(p.getUniqueId());
        if (sel != null && sel[0] != null && sel[1] != null && sel[0].getWorld() == sel[1].getWorld()) {
            long blocks = (long) (Math.abs(sel[0].getBlockX() - sel[1].getBlockX()) + 1)
                    * (Math.abs(sel[0].getBlockY() - sel[1].getBlockY()) + 1)
                    * (Math.abs(sel[0].getBlockZ() - sel[1].getBlockZ()) + 1);
            send(p, "wand-region", "%blocks%", String.valueOf(blocks));
        }
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("duel")) duelCommand(s, a);
        else tierCommand(s, a);
        return true;
    }

    private Player playerOnly(CommandSender s) {
        if (s instanceof Player p) return p;
        send(s, "player-only");
        return null;
    }

    private void duelCommand(CommandSender s, String[] a) {
        if (a.length == 0) {
            Player p = playerOnly(s);
            if (p != null) gui.open(p);
            return;
        }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                Player p = playerOnly(s);
                if (p != null) duels.joinQueue(p);
            }
            case "cancel" -> {
                Player p = playerOnly(s);
                if (p != null) duels.leaveQueue(p);
            }
            case "leave", "forfeit" -> {
                Player p = playerOnly(s);
                if (p != null) duels.forfeit(p);
            }
            case "wand" -> {
                if (!s.hasPermission("tierduels.admin")) {
                    send(s, "no-permission");
                    return;
                }
                Player p = playerOnly(s);
                if (p == null) return;
                var left = p.getInventory().addItem(createWand());
                for (ItemStack rest : left.values()) p.getWorld().dropItem(p.getLocation(), rest);
                send(p, "wand-given");
            }
            case "arena" -> arenaCommand(s, a);
            case "reload" -> {
                if (!s.hasPermission("tierduels.admin")) {
                    send(s, "no-permission");
                    return;
                }
                reloadConfig();
                duels.load();
                stats.load();
                send(s, "reloaded");
            }
            default -> send(s, "usage-duel");
        }
    }

    private void arenaCommand(CommandSender s, String[] a) {
        if (!s.hasPermission("tierduels.admin")) {
            send(s, "no-permission");
            return;
        }
        if (a.length < 2) {
            send(s, "arena-usage");
            return;
        }

        String sub = a[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (a.length < 3) {
                    send(s, "arena-usage");
                    return;
                }
                String name = a[2].toLowerCase(Locale.ROOT);
                if (duels.createArena(name)) send(s, "arena-created", "%arena%", name);
                else send(s, "arena-exists");
            }
            case "delete" -> {
                if (a.length < 3) {
                    send(s, "arena-usage");
                    return;
                }
                Arena arena = duels.arena(a[2]);
                if (arena == null) {
                    send(s, "arena-missing");
                } else if (arena.busy) {
                    send(s, "arena-busy");
                } else {
                    duels.deleteArena(a[2]);
                    send(s, "arena-deleted", "%arena%", a[2].toLowerCase(Locale.ROOT));
                }
            }
            case "setspawn", "setpos" -> {
                boolean spawn = sub.equals("setspawn");
                Player p = playerOnly(s);
                if (p == null) return;
                if (a.length < 4 || !(a[3].equals("1") || a[3].equals("2"))) {
                    send(s, spawn ? "spawn-usage" : "pos-usage");
                    return;
                }
                int index = Integer.parseInt(a[3]);
                String name = a[2].toLowerCase(Locale.ROOT);

                if (spawn) {
                    if (duels.setSpawn(name, index, p.getLocation())) {
                        send(s, "spawn-set", "%spawn%", a[3], "%arena%", name);
                    } else {
                        send(s, "arena-missing");
                    }
                } else {
                    // The block you look at, or the block you stand in
                    Block target = p.getTargetBlockExact(10);
                    Location loc = target != null ? target.getLocation() : p.getLocation().getBlock().getLocation();
                    if (duels.setPos(name, index, loc)) {
                        send(s, "pos-set", "%pos%", a[3], "%arena%", name,
                                "%x%", String.valueOf(loc.getBlockX()),
                                "%y%", String.valueOf(loc.getBlockY()),
                                "%z%", String.valueOf(loc.getBlockZ()));
                    } else {
                        send(s, "arena-missing");
                    }
                }
            }
            case "save" -> {
                if (a.length < 3) {
                    send(s, "arena-usage");
                    return;
                }
                String name = a[2].toLowerCase(Locale.ROOT);

                // Use the region picked with the wand, if there is one
                Location[] sel = s instanceof Player pl ? duels.selection(pl.getUniqueId()) : null;
                if (sel != null && sel[0] != null && sel[1] != null) {
                    duels.setPos(name, 1, sel[0]);
                    duels.setPos(name, 2, sel[1]);
                }
                DuelManager.SaveResult r = duels.saveSnapshot(name);
                send(s, r.key(), "%arena%", name, "%blocks%", String.valueOf(r.number()),
                        "%max%", String.valueOf(r.number()));
            }
            case "regen" -> {
                if (a.length < 3) {
                    send(s, "arena-usage");
                    return;
                }
                String name = a[2].toLowerCase(Locale.ROOT);
                send(s, duels.regen(name), "%arena%", name);
            }
            case "list" -> {
                if (duels.arenas().isEmpty()) {
                    send(s, "arena-list-empty");
                    return;
                }
                send(s, "arena-list-header");
                for (Arena arena : duels.arenas()) {
                    String status = !arena.ready() ? "&cspawns missing" : arena.busy ? "&6in use" : "&afree";
                    String regen = arena.snapshot != null ? "&aregen on"
                            : (arena.pos1 != null && arena.pos2 != null ? "&eregion set, not saved" : "&7no regen");
                    s.sendMessage(text("arena-list-line", "%arena%", arena.name, "%status%", status, "%regen%", regen));
                }
            }
            default -> send(s, "arena-usage");
        }
    }

    private UUID findId(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        return stats.findId(name);
    }

    private void tierCommand(CommandSender s, String[] a) {
        if (a.length == 0) {
            Player p = playerOnly(s);
            if (p != null) showTier(s, p.getName(), stats.get(p.getUniqueId(), p.getName()));
            return;
        }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "top" -> showTop(s);
            case "set" -> {
                if (!s.hasPermission("tierduels.admin")) {
                    send(s, "no-permission");
                    return;
                }
                if (a.length < 3) {
                    send(s, "tier-usage");
                    return;
                }
                Tier t = Tier.parse(a[2]);
                if (t == null) {
                    send(s, "tier-invalid");
                    return;
                }
                UUID id = findId(a[1]);
                if (id == null) {
                    send(s, "player-not-found");
                    return;
                }
                stats.setTier(id, t);
                refreshTab(id);
                send(s, "tier-set", "%player%", a[1], "%tier%", t.colored());
            }
            case "reset" -> {
                if (!s.hasPermission("tierduels.admin")) {
                    send(s, "no-permission");
                    return;
                }
                if (a.length < 2) {
                    send(s, "tier-usage");
                    return;
                }
                UUID id = findId(a[1]);
                if (id == null) {
                    send(s, "player-not-found");
                    return;
                }
                stats.reset(id);
                refreshTab(id);
                send(s, "tier-reset", "%player%", a[1]);
            }
            default -> {
                UUID id = findId(a[0]);
                PlayerStats st = id == null ? null : stats.peek(id);
                if (st == null) {
                    send(s, "tier-none", "%player%", a[0]);
                    return;
                }
                showTier(s, st.name, st);
            }
        }
    }

    private void showTier(CommandSender s, String name, PlayerStats st) {
        String key = st.tier.next() == null ? "tier-info-max" : "tier-info";
        s.sendMessage(msg(key,
                "%player%", name,
                "%tier%", st.tier.colored(),
                "%progress%", String.valueOf(st.progress),
                "%needed%", String.valueOf(winsPerTier()),
                "%wins%", String.valueOf(st.wins),
                "%losses%", String.valueOf(st.losses)));
    }

    private void showTop(CommandSender s) {
        List<PlayerStats> top = stats.top(10);
        if (top.isEmpty()) {
            send(s, "top-empty");
            return;
        }
        s.sendMessage(text("top-header"));
        int rank = 1;
        for (PlayerStats ps : top) {
            s.sendMessage(text("top-line",
                    "%rank%", String.valueOf(rank++),
                    "%tier%", ps.tier.colored(),
                    "%player%", ps.name,
                    "%wins%", String.valueOf(ps.wins)));
        }
    }

    // ------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("tierduels.admin");
        boolean duel = cmd.getName().equalsIgnoreCase("duel");

        if (duel) {
            if (a.length == 1) {
                out.addAll(List.of("join", "cancel", "leave"));
                if (admin) out.addAll(List.of("arena", "wand", "reload"));
            } else if (admin && a[0].equalsIgnoreCase("arena")) {
                if (a.length == 2) out.addAll(List.of("create", "delete", "setspawn", "setpos", "save", "regen", "list"));
                else if (a.length == 3 && !a[1].equalsIgnoreCase("create") && !a[1].equalsIgnoreCase("list")) {
                    out.addAll(duels.arenaNames());
                } else if (a.length == 4 && (a[1].equalsIgnoreCase("setspawn") || a[1].equalsIgnoreCase("setpos"))) {
                    out.addAll(List.of("1", "2"));
                }
            }
        } else {
            if (a.length == 1) {
                out.add("top");
                if (admin) out.addAll(List.of("set", "reset"));
                addOnline(out);
            } else if (admin && a.length == 2 && (a[0].equalsIgnoreCase("set") || a[0].equalsIgnoreCase("reset"))) {
                addOnline(out);
            } else if (admin && a.length == 3 && a[0].equalsIgnoreCase("set")) {
                for (Tier t : Tier.values()) out.add(t.label);
            }
        }

        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }

    private void addOnline(List<String> out) {
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
    }
}
