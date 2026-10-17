package dev.tierduels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The /duel menu: cancel queue (red), statistics (exp bottle), join queue (green). */
public final class QueueGui implements Listener {

    private static final int SLOT_CANCEL = 11;
    private static final int SLOT_STATS = 13;
    private static final int SLOT_JOIN = 15;

    /** Marks an inventory as the duel menu. */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final TierDuels plugin;
    private final DuelManager duels;

    QueueGui(TierDuels plugin, DuelManager duels) {
        this.plugin = plugin;
        this.duels = duels;
    }

    void open(Player p) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.text("gui-title"));
        holder.inventory = inv;

        if (plugin.getConfig().getBoolean("gui-filler", true)) {
            ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        }

        inv.setItem(SLOT_CANCEL, item(Material.RED_CONCRETE,
                plugin.text("gui-cancel-name"),
                List.of(plugin.text("gui-cancel-lore"))));

        inv.setItem(SLOT_STATS, statsItem(p));

        inv.setItem(SLOT_JOIN, item(Material.LIME_CONCRETE,
                plugin.text("gui-join-name"),
                List.of(plugin.text("gui-join-lore-1"),
                        plugin.text("gui-join-lore-2", "%count%", String.valueOf(duels.queueSize())))));

        p.openInventory(inv);
    }

    private ItemStack statsItem(Player p) {
        PlayerStats st = plugin.stats().get(p.getUniqueId(), p.getName());

        // Kills = duels won, deaths = duels lost
        double kd = st.losses == 0 ? st.wins : (double) st.wins / st.losses;

        List<Component> lore = new ArrayList<>();
        lore.add(plugin.text("gui-stats-kills", "%kills%", String.valueOf(st.wins)));
        lore.add(plugin.text("gui-stats-deaths", "%deaths%", String.valueOf(st.losses)));
        lore.add(plugin.text("gui-stats-kd", "%kd%", String.format(Locale.ROOT, "%.2f", kd)));
        lore.add(plugin.text("gui-stats-tier", "%tier%", st.tier.colored()));

        Tier next = st.tier.next();
        if (next != null) {
            lore.add(plugin.text("gui-stats-progress",
                    "%progress%", String.valueOf(st.progress),
                    "%needed%", String.valueOf(plugin.winsPerTier()),
                    "%next%", next.colored()));
        }
        return item(Material.EXPERIENCE_BOTTLE, plugin.text("gui-stats-name"), lore);
    }

    private ItemStack item(Material m, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));

        List<Component> lines = new ArrayList<>();
        for (Component c : lore) lines.add(c.decoration(TextDecoration.ITALIC, false));
        meta.lore(lines);

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        switch (e.getRawSlot()) {
            case SLOT_JOIN -> {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                p.closeInventory();
                duels.joinQueue(p);
            }
            case SLOT_CANCEL -> {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                p.closeInventory();
                duels.leaveQueue(p);
            }
            default -> {
                // statistics and filler do nothing
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
