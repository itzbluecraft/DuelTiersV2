package dev.tierduels;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ranks from lowest (None) to highest (LT1). */
enum Tier {
    NONE("None", "&8", NamedTextColor.DARK_GRAY),
    LT5("LT5", "&7", NamedTextColor.GRAY),
    LT4("LT4", "&a", NamedTextColor.GREEN),
    LT3("LT3", "&e", NamedTextColor.YELLOW),
    LT2("LT2", "&6", NamedTextColor.GOLD),
    LT1("LT1", "&c", NamedTextColor.RED);

    final String label;
    final String code;
    final NamedTextColor color;

    Tier(String label, String code, NamedTextColor color) {
        this.label = label;
        this.code = code;
        this.color = color;
    }

    /** The next tier up, or null at the top. */
    Tier next() {
        Tier[] all = values();
        return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
    }

    /** Colored label for use inside legacy (&) messages. */
    String colored() {
        return code + label;
    }

    static Tier parse(String s) {
        if (s == null) return null;
        for (Tier t : values()) {
            if (t.label.equalsIgnoreCase(s.trim())) return t;
        }
        return null;
    }
}

final class PlayerStats {
    String name = "";
    Tier tier = Tier.NONE;
    int progress;
    int wins;
    int losses;
}

final class Arena {
    final String name;
    Location spawn1;
    Location spawn2;
    /** Region corners; together with a saved snapshot they make the arena regenerate after duels. */
    Location pos1;
    Location pos2;
    ArenaSnapshot snapshot;
    boolean busy;

    Arena(String name) {
        this.name = name;
    }

    boolean ready() {
        return spawn1 != null && spawn2 != null;
    }
}

enum DuelState {
    COUNTDOWN, ACTIVE,
    /** The winner is collecting the loser's items; ends with /duel leave or when time runs out. */
    LOOTING,
    ENDED
}

final class Duel {
    final Arena arena;
    final UUID a;
    final UUID b;
    DuelState state;
    int countdown;
    long startedAt;
    UUID winner;
    long lootEndsAt;

    Duel(Arena arena, UUID a, UUID b, int countdown) {
        this.arena = arena;
        this.a = a;
        this.b = b;
        this.countdown = countdown;
        this.state = countdown > 0 ? DuelState.COUNTDOWN : DuelState.ACTIVE;
        this.startedAt = System.currentTimeMillis();
    }

    UUID opponent(UUID id) {
        return id.equals(a) ? b : a;
    }
}

/** What a duel changes about a player (position, game mode, effects) so it can be put back afterwards. */
final class SavedState {
    private final Location location;
    private final GameMode mode;
    private final List<PotionEffect> effects;

    private SavedState(Location location, GameMode mode, List<PotionEffect> effects) {
        this.location = location;
        this.mode = mode;
        this.effects = effects;
    }

    static SavedState capture(Player p) {
        return new SavedState(p.getLocation().clone(), p.getGameMode(),
                new ArrayList<>(p.getActivePotionEffects()));
    }

    /** Written to disk when a duel starts so a crash or restart can't leave anyone stuck in an arena. */
    void write(File file) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("location", location);
        cfg.set("mode", mode.name());
        cfg.set("effects", effects);
        try {
            cfg.save(file);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static SavedState read(File file) {
        if (!file.exists()) return null;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Location loc = cfg.getLocation("location");
        if (loc == null) return null;

        List<PotionEffect> eff = new ArrayList<>();
        for (Object o : cfg.getList("effects", new ArrayList<>())) {
            if (o instanceof PotionEffect pe) eff.add(pe);
        }

        GameMode gm;
        try {
            gm = GameMode.valueOf(cfg.getString("mode", "SURVIVAL"));
        } catch (IllegalArgumentException ex) {
            gm = GameMode.SURVIVAL;
        }
        return new SavedState(loc, gm, eff);
    }

    /** Teleports back, restores game mode and effects, and heals the player. Inventory is never touched. */
    @SuppressWarnings("deprecation")
    void restore(Player p) {
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(pe.getType());
        }
        for (PotionEffect pe : effects) {
            p.addPotionEffect(pe);
        }

        p.setGameMode(mode);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.teleport(location);
    }
}
