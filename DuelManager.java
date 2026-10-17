package dev.tierduels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Arenas, the duel queue, the duel lifecycle, arena regeneration and every event that protects a duel. */
public final class DuelManager implements Listener {

    /** Result of saving an arena snapshot: a message key plus a number for the message. */
    record SaveResult(String key, long number) {
    }

    private final TierDuels plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, Duel> duels = new HashMap<>();
    /** Players waiting for an opponent, in join order */
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, SavedState> saved = new HashMap<>();
    /** Wand selections of admins: {pos1, pos2} */
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final File arenaFile;
    private final File arenaDir;
    private final File pendingDir;

    DuelManager(TierDuels plugin) {
        this.plugin = plugin;
        this.arenaFile = new File(plugin.getDataFolder(), "arenas.yml");
        this.arenaDir = new File(plugin.getDataFolder(), "arenas");
        this.pendingDir = new File(plugin.getDataFolder(), "pending");
    }

    void load() {
        pendingDir.mkdirs();
        arenaDir.mkdirs();
        arenas.clear();

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(arenaFile);
        ConfigurationSection sec = cfg.getConfigurationSection("arenas");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                Arena a = new Arena(name);
                a.spawn1 = sec.getLocation(name + ".spawn1");
                a.spawn2 = sec.getLocation(name + ".spawn2");
                a.pos1 = sec.getLocation(name + ".pos1");
                a.pos2 = sec.getLocation(name + ".pos2");
                a.snapshot = ArenaSnapshot.read(snapFile(name));
                arenas.put(name.toLowerCase(Locale.ROOT), a);
            }
        }

        // An arena left dirty by a crash is put back right after startup
        Bukkit.getScheduler().runTask(plugin, this::recoverDirtyArenas);
    }

    private void recoverDirtyArenas() {
        for (Arena a : arenas.values()) {
            if (a.snapshot == null || !dirtyFile(a).exists() || a.busy) continue;
            if (Bukkit.getWorld(a.snapshot.world) == null) continue;
            a.busy = true;
            regenerate(a, 1, () -> finishArena(a));
        }
    }

    private void saveArenas() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            cfg.set("arenas." + a.name + ".spawn1", a.spawn1);
            cfg.set("arenas." + a.name + ".spawn2", a.spawn2);
            cfg.set("arenas." + a.name + ".pos1", a.pos1);
            cfg.set("arenas." + a.name + ".pos2", a.pos2);
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(arenaFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save arenas.yml: " + ex.getMessage());
        }
    }

    private File pendingFile(UUID id) {
        return new File(pendingDir, id + ".yml");
    }

    private File snapFile(String name) {
        return new File(arenaDir, name.toLowerCase(Locale.ROOT) + ".snap");
    }

    private File dirtyFile(Arena a) {
        return new File(arenaDir, a.name.toLowerCase(Locale.ROOT) + ".dirty");
    }

    // ---------------------------------------------------------------- arenas

    Arena arena(String name) {
        return arenas.get(name.toLowerCase(Locale.ROOT));
    }

    boolean createArena(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (arenas.containsKey(key)) return false;
        arenas.put(key, new Arena(key));
        saveArenas();
        return true;
    }

    boolean deleteArena(String name) {
        Arena removed = arenas.remove(name.toLowerCase(Locale.ROOT));
        if (removed == null) return false;
        snapFile(removed.name).delete();
        dirtyFile(removed).delete();
        saveArenas();
        return true;
    }

    boolean setSpawn(String name, int index, Location loc) {
        Arena a = arena(name);
        if (a == null) return false;
        if (index == 1) a.spawn1 = loc.clone();
        else a.spawn2 = loc.clone();
        saveArenas();
        return true;
    }

    boolean setPos(String name, int index, Location loc) {
        Arena a = arena(name);
        if (a == null) return false;
        if (index == 1) a.pos1 = loc.clone();
        else a.pos2 = loc.clone();
        saveArenas();
        return true;
    }

    /** Copies the blocks between the two corners; the arena is put back to this state after every duel. */
    SaveResult saveSnapshot(String name) {
        Arena a = arena(name);
        if (a == null) return new SaveResult("arena-missing", 0);
        if (a.busy) return new SaveResult("arena-busy", 0);
        if (a.pos1 == null || a.pos2 == null) return new SaveResult("region-missing", 0);
        if (a.pos1.getWorld() == null || a.pos1.getWorld() != a.pos2.getWorld()) {
            return new SaveResult("region-world", 0);
        }

        long max = plugin.getConfig().getLong("arena-max-blocks", 1000000L);
        ArenaSnapshot snap = ArenaSnapshot.capture(a.pos1.getWorld(), a.pos1, a.pos2, max);
        if (snap == null) return new SaveResult("arena-too-big", max);

        try {
            snap.write(snapFile(a.name));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save snapshot of " + a.name + ": " + ex.getMessage());
            return new SaveResult("arena-save-failed", 0);
        }
        a.snapshot = snap;
        dirtyFile(a).delete();
        return new SaveResult("arena-saved", snap.blockCount());
    }

    /** Manually regenerates an arena. Returns a message key. */
    String regen(String name) {
        Arena a = arena(name);
        if (a == null) return "arena-missing";
        if (a.busy) return "arena-busy";
        if (a.snapshot == null) return "arena-no-snapshot";
        a.busy = true;
        regenerate(a, 2, () -> finishArena(a));
        return "arena-regen-started";
    }

    void setSelection(UUID id, int index, Location loc) {
        selections.computeIfAbsent(id, k -> new Location[2])[index - 1] = loc.clone();
    }

    Location[] selection(UUID id) {
        return selections.get(id);
    }

    Collection<Arena> arenas() {
        return arenas.values();
    }

    List<String> arenaNames() {
        return new ArrayList<>(arenas.keySet());
    }

    private Arena freeArena() {
        List<Arena> free = new ArrayList<>();
        for (Arena a : arenas.values()) {
            if (a.ready() && !a.busy) free.add(a);
        }
        if (free.isEmpty()) return null;
        return free.get(ThreadLocalRandom.current().nextInt(free.size()));
    }

    private boolean hasReadyArena() {
        for (Arena a : arenas.values()) {
            if (a.ready()) return true;
        }
        return false;
    }

    /** The arena whose saved region contains this location, if any. */
    private Arena arenaAt(Location l) {
        if (l == null || l.getWorld() == null) return null;
        for (Arena a : arenas.values()) {
            ArenaSnapshot s = a.snapshot;
            if (s != null && s.inWorld(l.getWorld()) && s.contains(l.getBlockX(), l.getBlockY(), l.getBlockZ())) {
                return a;
            }
        }
        return null;
    }

    // ----------------------------------------------------------- regeneration

    private void markDirty(Arena a) {
        try {
            dirtyFile(a).createNewFile();
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not mark arena dirty: " + ex.getMessage());
        }
    }

    private void finishArena(Arena a) {
        dirtyFile(a).delete();
        a.busy = false;
        tryMatch();
    }

    private void clearEntities(World w, ArenaSnapshot s) {
        for (Entity en : w.getNearbyEntities(s.box())) {
            if (en instanceof Item || en instanceof Projectile || en instanceof EnderCrystal
                    || en instanceof TNTPrimed || en instanceof FallingBlock
                    || en instanceof ExperienceOrb || en instanceof AreaEffectCloud) {
                en.remove();
            }
        }
    }

    /**
     * Puts the arena back chunk by chunk over several ticks. Runs {@code passes} times
     * (a second pass catches liquids that kept flowing) and then calls done.
     */
    private void regenerate(Arena arena, int passes, Runnable done) {
        ArenaSnapshot snap = arena.snapshot;
        World w = snap == null ? null : Bukkit.getWorld(snap.world);
        if (snap == null || w == null) {
            done.run();
            return;
        }

        clearEntities(w, snap);
        List<int[]> chunks = snap.chunks();
        int perTick = Math.max(1, plugin.getConfig().getInt("regen-chunks-per-tick", 4));

        new BukkitRunnable() {
            private int next = 0;

            @Override
            public void run() {
                for (int i = 0; i < perTick && next < chunks.size(); i++, next++) {
                    int[] c = chunks.get(next);
                    snap.restoreChunk(w, c[0], c[1]);
                }
                if (next < chunks.size()) return;

                cancel();
                if (passes > 1) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> regenerate(arena, passes - 1, done), 20L);
                } else {
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---------------------------------------------------------------- queue

    boolean isInDuel(UUID id) {
        return duels.containsKey(id);
    }

    boolean inQueue(UUID id) {
        return queue.contains(id);
    }

    int queueSize() {
        return queue.size();
    }

    /** Puts a player in the queue. They are matched right away if someone else is waiting. */
    void joinQueue(Player p) {
        UUID id = p.getUniqueId();
        if (isInDuel(id)) {
            plugin.send(p, "you-in-duel");
            return;
        }
        if (queue.contains(id)) {
            plugin.send(p, "queue-already");
            return;
        }
        if (!hasReadyArena()) {
            plugin.send(p, "no-arena-setup");
            return;
        }

        boolean alone = queue.isEmpty();
        queue.add(id);
        plugin.send(p, "queue-joined");

        if (alone) {
            // Nobody to fight yet: tell the whole server
            Bukkit.broadcast(plugin.text("queue-broadcast-1", "%player%", p.getName()));
            Bukkit.broadcast(plugin.text("queue-broadcast-2"));
            return;
        }

        tryMatch();
        if (queue.contains(id)) plugin.send(p, "queue-wait-arena");
    }

    void leaveQueue(Player p) {
        if (queue.remove(p.getUniqueId())) plugin.send(p, "queue-left");
        else plugin.send(p, "queue-not-in");
    }

    /** Pairs waiting players, oldest first, as long as a free arena is available. */
    void tryMatch() {
        while (queue.size() >= 2) {
            Arena arena = freeArena();
            if (arena == null) return;

            List<UUID> pair = new ArrayList<>(2);
            for (UUID id : queue) {
                pair.add(id);
                if (pair.size() == 2) break;
            }
            queue.removeAll(pair);

            Player a = Bukkit.getPlayer(pair.get(0));
            Player b = Bukkit.getPlayer(pair.get(1));
            if (a == null || b == null || isInDuel(a.getUniqueId()) || isInDuel(b.getUniqueId())) {
                // One of them is gone: whoever is still available goes back in the queue
                if (a != null && !isInDuel(a.getUniqueId())) queue.add(a.getUniqueId());
                if (b != null && !isInDuel(b.getUniqueId())) queue.add(b.getUniqueId());
                continue;
            }
            start(a, b, arena);
        }
    }

    void forfeit(Player p) {
        Duel d = duels.get(p.getUniqueId());
        if (d == null) {
            plugin.send(p, "leave-none");
            return;
        }

        // The winner is done collecting the loser's items
        if (d.state == DuelState.LOOTING) {
            if (p.getUniqueId().equals(d.winner)) {
                plugin.send(p, "loot-left");
                finishLoot(d);
            } else {
                plugin.send(p, "leave-none");
            }
            return;
        }
        if (d.state == DuelState.ENDED) {
            plugin.send(p, "leave-none");
            return;
        }

        if (d.state == DuelState.COUNTDOWN) end(d, null, "a player left");
        else end(d, d.opponent(p.getUniqueId()), "forfeit");
    }

    // ------------------------------------------------------------ lifecycle

    private void start(Player a, Player b, Arena arena) {
        arena.busy = true;
        if (arena.snapshot != null) markDirty(arena);

        int countdown = Math.max(0, plugin.getConfig().getInt("countdown-seconds", 5));
        Duel d = new Duel(arena, a.getUniqueId(), b.getUniqueId(), countdown);
        duels.put(a.getUniqueId(), d);
        duels.put(b.getUniqueId(), d);
        queue.remove(a.getUniqueId());
        queue.remove(b.getUniqueId());

        for (Player p : new Player[]{a, b}) {
            SavedState st = SavedState.capture(p);
            saved.put(p.getUniqueId(), st);
            st.write(pendingFile(p.getUniqueId()));
            prepare(p);
        }

        a.teleport(arena.spawn1);
        b.teleport(arena.spawn2);

        StatsManager stats = plugin.stats();
        plugin.send(a, "duel-start", "%player%", b.getName(),
                "%tier%", stats.get(b.getUniqueId(), b.getName()).tier.colored());
        plugin.send(b, "duel-start", "%player%", a.getName(),
                "%tier%", stats.get(a.getUniqueId(), a.getName()).tier.colored());

        if (d.state == DuelState.COUNTDOWN) startCountdown(d);
        else d.startedAt = System.currentTimeMillis();
    }

    @SuppressWarnings("deprecation")
    private void prepare(Player p) {
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.closeInventory();
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(pe.getType());
        }
        p.setGameMode(GameMode.SURVIVAL);
        p.setFlying(false);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    private void title(Player p, Component main, Component sub, long fadeIn, long stay, long fadeOut) {
        p.showTitle(Title.title(main, sub, Title.Times.times(
                Duration.ofMillis(fadeIn), Duration.ofMillis(stay), Duration.ofMillis(fadeOut))));
    }

    /** 5 4 3 2 1 START, with both players frozen until START. */
    private void startCountdown(Duel d) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (d.state != DuelState.COUNTDOWN) {
                    cancel();
                    return;
                }
                Player a = Bukkit.getPlayer(d.a);
                Player b = Bukkit.getPlayer(d.b);
                if (a == null || b == null) {
                    cancel();
                    end(d, null, "a player left");
                    return;
                }

                if (d.countdown > 0) {
                    for (Player p : new Player[]{a, b}) {
                        title(p, Component.text(String.valueOf(d.countdown), NamedTextColor.YELLOW),
                                Component.empty(), 0, 900, 0);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    }
                    d.countdown--;
                } else {
                    d.state = DuelState.ACTIVE;
                    d.startedAt = System.currentTimeMillis();
                    for (Player p : new Player[]{a, b}) {
                        title(p, plugin.text("fight-title"), Component.empty(), 0, 1200, 300);
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.2f);
                    }
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 10L, 20L);
    }

    /** Runs every second: matchmaking, time limit and loot timer. */
    void tick() {
        long now = System.currentTimeMillis();
        tryMatch();

        int limit = plugin.getConfig().getInt("duel-time-limit-seconds", 300);
        for (Duel d : new HashSet<>(duels.values())) {
            if (d.state == DuelState.ACTIVE) {
                if (limit > 0 && now - d.startedAt > limit * 1000L) end(d, null, "time limit");
            } else if (d.state == DuelState.LOOTING) {
                Player w = d.winner == null ? null : Bukkit.getPlayer(d.winner);
                long left = (d.lootEndsAt - now + 999) / 1000;
                if (w == null || left <= 0) {
                    if (w != null) plugin.send(w, "loot-over");
                    finishLoot(d);
                } else {
                    w.sendActionBar(plugin.text("loot-actionbar", "%time%", formatTime(left)));
                }
            }
        }
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        PlayerStats s = plugin.stats().peek(id);
        return s != null && !s.name.isEmpty() ? s.name : "Unknown";
    }

    private int promotionDelay() {
        return Math.max(0, plugin.getConfig().getInt("promotion-delay-seconds", 5));
    }

    /** Ends a duel. winner == null means a draw (no tier change). */
    void end(Duel d, UUID winner, String reason) {
        if (d.state == DuelState.ENDED || d.state == DuelState.LOOTING) return;
        d.state = DuelState.ENDED;

        boolean broadcast = plugin.getConfig().getBoolean("broadcast-results", true);
        boolean loot = winner != null && plugin.getConfig().getBoolean("loser-drops-items", true);

        if (winner != null) {
            UUID loser = d.opponent(winner);
            String wName = nameOf(winner);
            String lName = nameOf(loser);
            StatsManager stats = plugin.stats();

            boolean pending = stats.recordWin(winner, wName);
            stats.recordLoss(loser, lName);
            PlayerStats ws = stats.get(winner, wName);
            Tier next = ws.tier.next();
            String needed = String.valueOf(plugin.winsPerTier());
            int promoDelay = promotionDelay();

            Player wp = Bukkit.getPlayer(winner);
            Player lp = Bukkit.getPlayer(loser);

            if (wp != null) {
                plugin.send(wp, "win", "%player%", lName);
                if (next == null) {
                    title(wp, plugin.text("win-title"), plugin.text("top-tier-subtitle"), 200, 3000, 500);
                } else if (pending) {
                    // 3/3: show it, then promote after a short wait
                    title(wp, plugin.text("progress-title", "%progress%", String.valueOf(ws.progress), "%needed%", needed),
                            plugin.text("promotion-soon-subtitle", "%next%", next.colored(), "%time%", String.valueOf(promoDelay)),
                            200, 4000, 500);
                    plugin.send(wp, "promotion-soon", "%progress%", String.valueOf(ws.progress), "%needed%", needed,
                            "%next%", next.colored(), "%time%", String.valueOf(promoDelay));
                } else {
                    title(wp, plugin.text("progress-title", "%progress%", String.valueOf(ws.progress), "%needed%", needed),
                            plugin.text("progress-subtitle", "%next%", next.colored()),
                            200, 3000, 500);
                    plugin.send(wp, "progress", "%next%", next.colored(),
                            "%progress%", String.valueOf(ws.progress), "%needed%", needed);
                }
                wp.playSound(wp.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            }
            if (lp != null) {
                plugin.send(lp, "lose", "%player%", wName);
                if (loot) plugin.send(lp, "lose-items");
                title(lp, plugin.text("lose-title"), Component.empty(), 200, 2500, 500);
            }

            if (broadcast) {
                Bukkit.broadcast(plugin.msg("result-broadcast", "%winner%", wName, "%loser%", lName,
                        "%tier%", ws.tier.colored()));
            }
            if (pending) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> applyPromotion(winner), promoDelay * 20L);
            }
            plugin.refreshTab(winner);
            plugin.refreshTab(loser);
        } else {
            for (UUID id : new UUID[]{d.a, d.b}) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) plugin.send(p, "draw", "%reason%", reason);
            }
        }

        long delay = Math.max(1, plugin.getConfig().getInt("end-delay-seconds", 3)) * 20L;

        if (loot) {
            // The winner stays in the arena to collect the loser's items
            int lootSeconds = Math.max(10, plugin.getConfig().getInt("loot-time-seconds", 300));
            UUID loser = d.opponent(winner);
            d.winner = winner;
            d.lootEndsAt = System.currentTimeMillis() + lootSeconds * 1000L;
            d.state = DuelState.LOOTING;

            Player lp = Bukkit.getPlayer(loser);
            if (lp != null && !lp.isDead()) dropLoot(lp, winner);

            Player wp = Bukkit.getPlayer(winner);
            if (wp != null) plugin.send(wp, "loot-start", "%time%", formatTime(lootSeconds));

            Bukkit.getScheduler().runTaskLater(plugin, () -> restoreLoser(d, loser), delay);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(d), delay);
        }

        // A player who really died (totem fallback) must respawn before they can be restored
        for (UUID id : new UUID[]{d.a, d.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isDead()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> reviveAndRestore(id, d), 1L);
            }
        }
    }

    /** The loser's whole inventory drops where they stand; only the winner can pick it up. */
    private void dropLoot(Player loser, UUID winner) {
        loser.closeInventory();
        PlayerInventory inv = loser.getInventory();
        Location loc = loser.getLocation();
        World w = loc.getWorld();

        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType().isAir()) continue;
            Item drop = w.dropItemNaturally(loc, item);
            drop.setOwner(winner);
        }
        inv.clear();
    }

    /** After the end delay the loser goes back (without the items the winner can now take). */
    private void restoreLoser(Duel d, UUID loser) {
        if (duels.get(loser) == d) duels.remove(loser);
        Player p = Bukkit.getPlayer(loser);
        if (p != null && !p.isDead()) restore(p);
    }

    /** Looting is over (/duel leave, out of time, or the winner left): everyone goes back, the arena regenerates. */
    void finishLoot(Duel d) {
        if (d.state != DuelState.LOOTING) return;
        d.state = DuelState.ENDED;
        cleanup(d);
    }

    private static String formatTime(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** The wait after 3/3 is over: the player goes up a tier. */
    void applyPromotion(UUID id) {
        Tier tier = plugin.stats().promoteIfReady(id);
        if (tier == null) return;

        plugin.refreshTab(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            title(p, plugin.text("promoted-title"),
                    plugin.text("promoted-subtitle", "%tier%", tier.colored()), 300, 4000, 800);
            plugin.send(p, "promoted", "%tier%", tier.colored());
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
        if (plugin.getConfig().getBoolean("broadcast-results", true)) {
            Bukkit.broadcast(plugin.msg("promoted-broadcast", "%player%", nameOf(id), "%tier%", tier.colored()));
        }
    }

    private void cleanup(Duel d) {
        for (UUID id : new UUID[]{d.a, d.b}) {
            if (duels.get(id) == d) duels.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && !p.isDead()) restore(p);
        }

        // The arena stays busy until every block is back
        if (d.arena.snapshot != null) {
            regenerate(d.arena, 2, () -> finishArena(d.arena));
        } else {
            d.arena.busy = false;
        }
    }

    private void reviveAndRestore(UUID id, Duel d) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        if (p.isDead()) p.spigot().respawn();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null && !q.isDead()) {
                restore(q);
                if (duels.get(id) == d) duels.remove(id);
            }
        }, 2L);
    }

    /** Puts a player's location and state back. Also recovers after crashes. */
    void restore(Player p) {
        UUID id = p.getUniqueId();
        SavedState st = saved.remove(id);
        if (st == null) st = SavedState.read(pendingFile(id));
        if (st == null) return;
        st.restore(p);
        pendingFile(id).delete();
    }

    void onJoin(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !isInDuel(p.getUniqueId())) restore(p);
        }, 10L);
    }

    void shutdown() {
        queue.clear();
        for (Duel d : new HashSet<>(duels.values())) {
            d.state = DuelState.ENDED;
            for (UUID id : new UUID[]{d.a, d.b}) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) restore(p);
            }
        }
        duels.clear();

        // Put every arena that was in use (or still regenerating) back right now
        for (Arena a : arenas.values()) {
            if (a.snapshot == null || !(a.busy || dirtyFile(a).exists())) continue;
            World w = Bukkit.getWorld(a.snapshot.world);
            if (w != null) {
                clearEntities(w, a.snapshot);
                a.snapshot.restoreAll(w);
            }
            dirtyFile(a).delete();
            a.busy = false;
        }
    }

    // --------------------------------------------------------------- events

    private boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    /** Lethal damage never kills: the duel just ends and the other player wins. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Duel d = duels.get(p.getUniqueId());
        if (d == null) return;

        if (d.state != DuelState.ACTIVE) {
            e.setCancelled(true);
            return;
        }
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        if (hasTotem(p)) return;

        e.setCancelled(true);
        end(d, d.opponent(p.getUniqueId()), "kill");
    }

    /** Duelists can only hurt each other, and nobody else can hurt duelists. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        Entity damager = e.getDamager();
        if (damager instanceof Player pl) {
            attacker = pl;
        } else if (damager instanceof Projectile pr && pr.getShooter() instanceof Player pl) {
            attacker = pl;
        }

        Duel dv = duels.get(victim.getUniqueId());
        Duel da = attacker == null ? null : duels.get(attacker.getUniqueId());
        if (dv == null && da == null) return;

        if (dv == null || dv != da || dv.state != DuelState.ACTIVE) e.setCancelled(true);
    }

    /** Fallback when a player really dies (e.g. a totem pop followed by another hit). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Duel d = duels.get(p.getUniqueId());
        if (d == null) return;

        // With loot on, a real death drops the items where the loser fell, like a normal death
        if (!plugin.getConfig().getBoolean("loser-drops-items", true)) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        }
        e.deathMessage(null);

        if (d.state != DuelState.ENDED) {
            UUID winner = d.state == DuelState.ACTIVE ? d.opponent(p.getUniqueId()) : null;
            Bukkit.getScheduler().runTask(plugin, () -> end(d, winner, "kill"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        queue.remove(id);
        selections.remove(id);

        Duel d = duels.get(id);
        if (d == null) return;

        if (d.state == DuelState.COUNTDOWN) end(d, null, "a player left");
        else if (d.state == DuelState.ACTIVE) end(d, d.opponent(id), "left the server");
        else if (d.state == DuelState.LOOTING && id.equals(d.winner)) finishLoot(d);

        // Send the leaving player back right away
        restore(p);
        duels.remove(id);
    }

    /** Players are frozen during the countdown. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Duel d = duels.get(e.getPlayer().getUniqueId());
        if (d == null || d.state != DuelState.COUNTDOWN) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    // Building is only allowed inside the saved region of an arena (it is put back after the duel).

    private boolean buildBlocked(Player p, Block b) {
        Duel d = duels.get(p.getUniqueId());
        if (d == null) return false;
        if (d.state != DuelState.ACTIVE) return true;
        ArenaSnapshot s = d.arena.snapshot;
        if (s == null) return true;
        return !s.inWorld(b.getWorld()) || !s.contains(b.getX(), b.getY(), b.getZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (buildBlocked(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (buildBlocked(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (buildBlocked(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (buildBlocked(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    /** Explosions inside an arena only break blocks inside its region. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Arena a = arenaAt(e.getLocation());
        if (a == null) return;
        ArenaSnapshot s = a.snapshot;
        e.blockList().removeIf(b -> !s.contains(b.getX(), b.getY(), b.getZ()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Arena a = arenaAt(e.getBlock().getLocation());
        if (a == null) return;
        ArenaSnapshot s = a.snapshot;
        e.blockList().removeIf(b -> !s.contains(b.getX(), b.getY(), b.getZ()));
    }

    /** Water and lava can't flow out of an arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (arenaAt(e.getBlock().getLocation()) != null && arenaAt(e.getToBlock().getLocation()) == null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isInDuel(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isInDuel(p.getUniqueId()) || p.hasPermission("tierduels.bypass")) return;

        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon != -1) label = label.substring(colon + 1);

        for (String allowed : plugin.getConfig().getStringList("duel-allowed-commands")) {
            if (allowed.equalsIgnoreCase(label)) return;
        }
        e.setCancelled(true);
        plugin.send(p, "commands-blocked");
    }
}
