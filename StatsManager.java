package dev.tierduels;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tiers, win progress and the leaderboard, saved to stats.yml. */
public final class StatsManager {

    private final TierDuels plugin;
    private final File file;
    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();

    public StatsManager(TierDuels plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    public void load() {
        stats.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("players");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            PlayerStats s = new PlayerStats();
            s.name = sec.getString(key + ".name", "");
            Tier t = Tier.parse(sec.getString(key + ".tier", "None"));
            s.tier = t == null ? Tier.NONE : t;
            s.progress = sec.getInt(key + ".progress", 0);
            s.wins = sec.getInt(key + ".wins", 0);
            s.losses = sec.getInt(key + ".losses", 0);
            stats.put(id, s);
        }
    }

    public synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerStats> e : stats.entrySet()) {
            String base = "players." + e.getKey();
            PlayerStats s = e.getValue();
            cfg.set(base + ".name", s.name);
            cfg.set(base + ".tier", s.tier.label);
            cfg.set(base + ".progress", s.progress);
            cfg.set(base + ".wins", s.wins);
            cfg.set(base + ".losses", s.losses);
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save stats.yml: " + ex.getMessage());
        }
    }

    /** Gets (or creates) the stats of a player, updating the stored name. */
    public PlayerStats get(UUID id, String name) {
        PlayerStats s = stats.computeIfAbsent(id, k -> {
            PlayerStats created = new PlayerStats();
            created.tier = plugin.startTier();
            return created;
        });
        if (name != null && !name.isEmpty()) s.name = name;
        return s;
    }

    public PlayerStats peek(UUID id) {
        return stats.get(id);
    }

    public UUID findId(String name) {
        for (Map.Entry<UUID, PlayerStats> e : stats.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /**
     * Records a win. Returns true when the player now has every win needed for the next tier.
     * The promotion itself happens later through promoteIfReady().
     */
    public boolean recordWin(UUID id, String name) {
        promoteIfReady(id); // a promotion that was still waiting
        PlayerStats s = get(id, name);
        s.wins++;

        boolean pending = false;
        if (s.tier.next() != null) {
            s.progress++;
            pending = s.progress >= plugin.winsPerTier();
        }
        save();
        return pending;
    }

    /** Promotes a player who has all the wins for the next tier. Returns the new tier or null. */
    public Tier promoteIfReady(UUID id) {
        PlayerStats s = stats.get(id);
        if (s == null) return null;
        Tier next = s.tier.next();
        if (next == null || s.progress < plugin.winsPerTier()) return null;
        s.tier = next;
        s.progress = 0;
        save();
        return next;
    }

    public void recordLoss(UUID id, String name) {
        get(id, name).losses++;
        save();
    }

    public void setTier(UUID id, Tier tier) {
        PlayerStats s = get(id, null);
        s.tier = tier;
        s.progress = 0;
        save();
    }

    public void reset(UUID id) {
        PlayerStats s = get(id, null);
        s.tier = plugin.startTier();
        s.progress = 0;
        s.wins = 0;
        s.losses = 0;
        save();
    }

    /** Best players first: tier, then progress, then total wins. */
    public List<PlayerStats> top(int n) {
        List<PlayerStats> list = new ArrayList<>();
        for (PlayerStats s : stats.values()) {
            if (s.wins + s.losses > 0) list.add(s);
        }
        list.sort((x, y) -> {
            int c = Integer.compare(y.tier.ordinal(), x.tier.ordinal());
            if (c != 0) return c;
            c = Integer.compare(y.progress, x.progress);
            if (c != 0) return c;
            return Integer.compare(y.wins, x.wins);
        });
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }
}
