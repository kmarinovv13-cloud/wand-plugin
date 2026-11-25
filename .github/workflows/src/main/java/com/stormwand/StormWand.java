package com.stormwand;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StormWand extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String USAGE =
            "Usage: /stormwand <give [player]|kills [player]|reset [player]|setkills <player> <number>|reload>";

    private NamespacedKey wandKey;
    private NamespacedKey stageKey;
    private NamespacedKey killsKey;
    private NamespacedKey victimsKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "storm_wand");
        stageKey = new NamespacedKey(this, "wand_stage");
        killsKey = new NamespacedKey(this, "kills");
        victimsKey = new NamespacedKey(this, "victims");
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("stormwand");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        // Keeps every wand's look in sync with its owner's stage.
        getServer().getScheduler().runTaskTimer(this, this::refreshAllPlayers, 40L, 20L);
    }

    // ------------------------------------------------------------------ progression

    /** Total player kills, counting repeats. */
    private int getKills(Player player) {
        return player.getPersistentDataContainer().getOrDefault(killsKey, PersistentDataType.INTEGER, 0);
    }

    /** UUIDs (as text) of every different player this player has killed. */
    private Set<String> getVictims(Player player) {
        String raw = player.getPersistentDataContainer().get(victimsKey, PersistentDataType.STRING);
        Set<String> victims = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            victims.addAll(Arrays.asList(raw.split(",")));
        }
        return victims;
    }

    private void saveVictims(Player player, Set<String> victims) {
        player.getPersistentDataContainer().set(victimsKey, PersistentDataType.STRING, String.join(",", victims));
    }

    private boolean uniqueOnly() {
        return getConfig().getBoolean("progression.unique-victims-only", true);
    }

    /** The number that decides the wand stage: different players killed, or total kills. */
    private int getProgress(Player player) {
        return uniqueOnly() ? getVictims(player).size() : getKills(player);
    }

    /** Admin/testing helper: sets progress to the given number. */
    private void setProgress(Player player, int amount) {
        int count = Math.min(Math.max(0, amount), 1000);
        Set<String> victims = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            victims.add(UUID.randomUUID().toString());
        }
        saveVictims(player, victims);
        player.getPersistentDataContainer().set(killsKey, PersistentDataType.INTEGER, count);
        refreshWands(player);
    }

    private int stageFor(int progress) {
        FileConfiguration cfg = getConfig();
        int forDebuffs = cfg.getInt("progression.kills-for-debuffs", 1);
        int forBuffs = cfg.getInt("progression.kills-for-buffs", 3);
        if (progress >= forBuffs) {
            return 3;
        }
        if (progress >= forDebuffs) {
            return 2;
        }
        return 1;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return;
        }

        FileConfiguration cfg = getConfig();
        int forDebuffs = cfg.getInt("progression.kills-for-debuffs", 1);
        int forBuffs = cfg.getInt("progression.kills-for-buffs", 3);

        int before = getProgress(killer);

        // Always remember the total kill count.
        killer.getPersistentDataContainer().set(killsKey, PersistentDataType.INTEGER, getKills(killer) + 1);

        // Remember each different victim.
        Set<String> victims = getVictims(killer);
        if (victims.add(victim.getUniqueId().toString())) {
            saveVictims(killer, victims);
        }

        int after = getProgress(killer);
        if (after == before) {
            if (uniqueOnly()) {
                killer.sendActionBar(message("messages.already-counted",
                        "<gray>You already counted this player."));
            }
            return;
        }

        refreshWands(killer);

        if (before < forDebuffs && after >= forDebuffs) {
            killer.sendMessage(message("messages.unlocked-debuffs",
                    "<gold>Storm Wand: <yellow>Debuffs unlocked!"));
        }
        if (before < forBuffs && after >= forBuffs) {
            killer.sendMessage(message("messages.unlocked-buffs",
                    "<gold>Storm Wand: <yellow>Final power unlocked!"));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshWands(event.getPlayer());
    }

    // ------------------------------------------------------------------ item

    @SuppressWarnings("deprecation")
    private void applyAppearance(ItemMeta meta, int stage) {
        FileConfiguration cfg = getConfig();
        String base = "wand.stages.stage-" + stage;

        meta.displayName(noItalic(MM.deserialize(cfg.getString(base + ".name", "<gold>Storm Wand"))));

        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList(base + ".lore")) {
            lore.add(noItalic(MM.deserialize(line)));
        }
        meta.lore(lore);

        meta.setEnchantmentGlintOverride(cfg.getBoolean(base + ".glow", true));

        int modelData = cfg.getInt(base + ".custom-model-data", 0);
        try {
            meta.setCustomModelData(modelData > 0 ? modelData : null);
        } catch (Throwable t) {
            if (modelData > 0) {
                getLogger().warning("custom-model-data is not supported on this server version.");
            }
        }

        String model = cfg.getString(base + ".item-model", "");
        NamespacedKey modelKey = null;
        if (model != null && !model.isBlank()) {
            modelKey = NamespacedKey.fromString(model.trim());
            if (modelKey == null) {
                getLogger().warning(base + ".item-model is not a valid key: " + model);
            }
        }
        try {
            meta.setItemModel(modelKey);
        } catch (Throwable t) {
            if (modelKey != null) {
                getLogger().warning("item-model is not supported on this server version.");
            }
        }

        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(stageKey, PersistentDataType.INTEGER, stage);
    }

    private ItemStack createWand(int stage) {
        Material material = Material.matchMaterial(getConfig().getString("wand.material", "BLAZE_ROD"));
        if (material == null || material.isAir() || !material.isItem()) {
            getLogger().warning("Invalid wand.material in config, using BLAZE_ROD.");
            material = Material.BLAZE_ROD;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        applyAppearance(meta, stage);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /** Updates every wand in the player's inventory to match the player's current stage. */
    private void refreshWands(Player player) {
        int stage = stageFor(getProgress(player));
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isWand(stack)) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            Integer current = meta.getPersistentDataContainer().get(stageKey, PersistentDataType.INTEGER);
            if (current != null && current == stage) {
                continue;
            }
            applyAppearance(meta, stage);
            stack.setItemMeta(meta);
            inventory.setItem(i, stack);
        }
    }

    private void refreshAllPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshWands(player);
        }
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------------ casting

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!isWand(player.getInventory().getItemInMainHand())) {
            return;
        }

        event.setCancelled(true);

        if (!player.hasPermission("stormwand.use")) {
            return;
        }
        cast(player);
    }

    private void cast(Player player) {
        FileConfiguration cfg = getConfig();
        long now = System.currentTimeMillis();

        long readyAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long secondsLeft = (long) Math.ceil((readyAt - now) / 1000.0);
            player.sendActionBar(message("messages.on-cooldown",
                    "<red>Storm Wand recharging: <yellow><seconds>s",
                    Placeholder.unparsed("seconds", String.valueOf(secondsLeft))));
            return;
        }

        int stage = stageFor(getProgress(player));

        LivingEntity target = findTarget(player);
        if (target == null && cfg.getBoolean("require-target", true)) {
            player.sendActionBar(message("messages.no-target", "<gray>No target in range."));
            return;
        }

        // Stage 1: lightning (always).
        if (target != null) {
            strike(player, target);

            // Stage 2: debuffs on the target.
            if (stage >= 2) {
                applyEffects(target, cfg.getConfigurationSection("target-effects"));
            }
        }

        // Stage 3: buffs for the wielder.
        if (stage >= 3) {
            applyEffects(player, cfg.getConfigurationSection("self-effects"));
        }

        long cooldownMillis = (long) (cfg.getDouble("cooldown-seconds", 60.0) * 1000.0);
        cooldowns.put(player.getUniqueId(), now + cooldownMillis);
    }

    private LivingEntity findTarget(Player player) {
        FileConfiguration cfg = getConfig();
        double range = cfg.getDouble("range", 30.0);
        boolean hitPlayers = cfg.getBoolean("affect-players", true);
        boolean hitMobs = cfg.getBoolean("affect-mobs", true);

        Location eye = player.getEyeLocation();
        RayTraceResult result = player.getWorld().rayTrace(
                eye,
                eye.getDirection(),
                range,
                FluidCollisionMode.NEVER,
                true,
                0.3,
                entity -> {
                    if (entity.equals(player) || !(entity instanceof LivingEntity)) {
                        return false;
                    }
                    if (entity instanceof ArmorStand) {
                        return false;
                    }
                    if (entity instanceof Player other) {
                        return hitPlayers && other.getGameMode() != GameMode.SPECTATOR;
                    }
                    return hitMobs;
                });

        if (result == null) {
            return null;
        }
        Entity hit = result.getHitEntity();
        return hit instanceof LivingEntity living ? living : null;
    }

    private void strike(Player caster, LivingEntity target) {
        FileConfiguration cfg = getConfig();
        Location location = target.getLocation();

        if (cfg.getBoolean("lightning.real-lightning", false)) {
            target.getWorld().strikeLightning(location);
        } else {
            target.getWorld().strikeLightningEffect(location);
            double damage = cfg.getDouble("lightning.damage", 4.0);
            if (damage > 0) {
                target.damage(damage, caster);
            }
        }
    }

    private void applyEffects(LivingEntity entity, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection effect = section.getConfigurationSection(name);
            if (effect == null) {
                continue;
            }

            PotionEffectType type = null;
            try {
                type = PotionEffectType.getByKey(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // falls through to the warning below
            }
            if (type == null) {
                getLogger().warning("Unknown potion effect in config: " + name);
                continue;
            }

            int ticks = (int) Math.round(effect.getDouble("duration-seconds", 10.0) * 20.0);
            int amplifier = Math.max(1, effect.getInt("level", 1)) - 1;
            if (ticks <= 0) {
                continue;
            }

            entity.addPotionEffect(new PotionEffect(
                    type,
                    ticks,
                    amplifier,
                    effect.getBoolean("ambient", false),
                    effect.getBoolean("particles", true),
                    effect.getBoolean("icon", true)));
        }
    }

    private Component message(String path, String fallback, TagResolver... resolvers) {
        return MM.deserialize(getConfig().getString(path, fallback), resolvers);
    }

    // ------------------------------------------------------------------ commands

    private Player resolvePlayer(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            return Bukkit.getPlayerExact(args[index]);
        }
        if (sender instanceof Player self) {
            return self;
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text(USAGE));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("stormwand.reload")) {
                    sender.sendMessage(Component.text("You don't have permission to do that."));
                    return true;
                }
                reloadConfig();
                sender.sendMessage(Component.text("StormWand config reloaded."));
            }
            case "give" -> {
                if (!sender.hasPermission("stormwand.give")) {
                    sender.sendMessage(Component.text("You don't have permission to do that."));
                    return true;
                }
                Player target = resolvePlayer(sender, args, 1);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found. Usage: /stormwand give <player>"));
                    return true;
                }
                ItemStack wand = createWand(stageFor(getProgress(target)));
                Map<Integer, ItemStack> leftover = target.getInventory().addItem(wand);
                for (ItemStack extra : leftover.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), extra);
                }
                sender.sendMessage(Component.text("Gave the Storm Wand to " + target.getName() + "."));
            }
            case "kills" -> {
                if (args.length > 1 && !sender.hasPermission("stormwand.admin")) {
                    sender.sendMessage(Component.text("You don't have permission to check other players."));
                    return true;
                }
                Player target = resolvePlayer(sender, args, 1);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found. Usage: /stormwand kills [player]"));
                    return true;
                }
                int progress = getProgress(target);
                String what = uniqueOnly() ? "different players killed" : "kills";
                sender.sendMessage(Component.text(target.getName() + ": " + progress + " " + what
                        + " (total kills " + getKills(target) + "), wand stage " + stageFor(progress) + " of 3."));
            }
            case "reset" -> {
                if (!sender.hasPermission("stormwand.admin")) {
                    sender.sendMessage(Component.text("You don't have permission to do that."));
                    return true;
                }
                Player target = resolvePlayer(sender, args, 1);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found. Usage: /stormwand reset [player]"));
                    return true;
                }
                setProgress(target, 0);
                sender.sendMessage(Component.text("Reset " + target.getName() + "'s progress to 0."));
            }
            case "setkills" -> {
                if (!sender.hasPermission("stormwand.admin")) {
                    sender.sendMessage(Component.text("You don't have permission to do that."));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Usage: /stormwand setkills <player> <number>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found."));
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("That's not a number: " + args[2]));
                    return true;
                }
                setProgress(target, amount);
                sender.sendMessage(Component.text("Set " + target.getName() + "'s progress to "
                        + Math.min(Math.max(0, amount), 1000) + "."));
            }
            default -> sender.sendMessage(Component.text(USAGE));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return List.of("give", "kills", "reset", "setkills", "reload").stream()
                    .filter(s -> s.startsWith(typed))
                    .toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("give") || sub.equals("kills") || sub.equals("reset") || sub.equals("setkills")) {
                String typed = args[1].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                        .toList();
            }
        }
        return List.of();
    }
}
