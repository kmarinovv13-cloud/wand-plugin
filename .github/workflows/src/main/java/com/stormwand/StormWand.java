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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class StormWand extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private NamespacedKey wandKey;
    private File killsFile;
    private FileConfiguration killsData;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        wandKey = new NamespacedKey(this, "storm_wand");
        killsFile = new File(getDataFolder(), "kills.yml");
        if (!killsFile.exists()) {
            try {
                getDataFolder().mkdirs();
                killsFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create kills.yml: " + e.getMessage());
            }
        }
        killsData = YamlConfiguration.loadConfiguration(killsFile);

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("stormwand");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    private int getKills(Player player) {
        return killsData.getInt(player.getUniqueId().toString(), 0);
    }

    private int getStage(int kills) {
        if (kills >= 10) return 3;
        if (kills >= 5) return 2;
        return 1;
    }

    private void saveKills() {
        try {
            killsData.save(killsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save kills.yml: " + e.getMessage());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        UUID id = killer.getUniqueId();
        int newKills = getKills(killer) + 1;
        killsData.set(id.toString(), newKills);
        saveKills();

        // Update the model of any Storm Wands in the killer's inventory.
        updateWands(killer, getStage(newKills));

        if (newKills == 5 || newKills == 10) {
            killer.sendMessage(Component.text(
                    "Storm Wand upgraded to Stage " + getStage(newKills)
                            + " after " + newKills + " kills!"));
        }
    }

    private ItemStack createWand(int stage) {
        FileConfiguration cfg = getConfig();

        Material material = Material.matchMaterial(cfg.getString("wand.material", "BLAZE_ROD"));
        if (material == null || material.isAir() || !material.isItem()) {
            material = Material.BLAZE_ROD;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(noItalic(MM.deserialize(
                cfg.getString("wand.name", "<gold>Storm Wand"))));

        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList("wand.lore")) {
            lore.add(noItalic(MM.deserialize(line)));
        }
        meta.lore(lore);

        if (cfg.getBoolean("wand.glow", true)) {
            meta.setEnchantmentGlintOverride(true);
        }

        int modelData = cfg.getInt("wand.stages." + stage + ".custom-model-data", 0);
        if (modelData > 0) {
            meta.setCustomModelData(modelData);
        }

        setStageItemModel(meta, stage);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void setStageItemModel(ItemMeta meta, int stage) {
        String model = getConfig().getString("wand.stages." + stage + ".item-model", "");
        if (model == null || model.isBlank()) return;

        NamespacedKey key = NamespacedKey.fromString(model.trim());
        if (key == null) {
            getLogger().warning("Invalid item model for wand stage " + stage + ": " + model);
            return;
        }
        meta.setItemModel(key);
    }

    private void updateWands(Player player, int stage) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (!isWand(item)) continue;

            ItemMeta meta = item.getItemMeta();
            int modelData = getConfig().getInt("wand.stages." + stage + ".custom-model-data", 0);
            if (modelData > 0) {
                meta.setCustomModelData(modelData);
            }
            setStageItemModel(meta, stage);
            item.setItemMeta(meta);
        }
        player.getInventory().setContents(contents);
    }

    private boolean isWand(ItemStack item) {
        return item != null
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                        .has(wandKey, PersistentDataType.BYTE);
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(
                TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!isWand(player.getInventory().getItemInMainHand())) return;

        event.setCancelled(true);
        if (!player.hasPermission("stormwand.use")) return;

        // Keep the model in sync if the player earned kills before receiving this wand.
        updateWands(player, getStage(getKills(player)));
        cast(player);
    }

    private void cast(Player player) {
        FileConfiguration cfg = getConfig();
        long now = System.currentTimeMillis();

        long readyAt = getConfig().getLong(
                "cooldowns." + player.getUniqueId(), 0L);
        if (now < readyAt) {
            long secondsLeft = (long) Math.ceil((readyAt - now) / 1000.0);
            player.sendActionBar(message(
                    "messages.on-cooldown",
                    "<red>Storm Wand recharging: <yellow><seconds>s",
                    Placeholder.unparsed("seconds", String.valueOf(secondsLeft))));
            return;
        }

        LivingEntity target = findTarget(player);
        if (target == null && cfg.getBoolean("require-target", true)) {
            player.sendActionBar(message("messages.no-target", "<gray>No target in range."));
            return;
        }

        int stage = getStage(getKills(player));

        // Lightning is available from the start.
        if (target != null) {
            strike(player, target);

            // Stage 2: target debuffs, unlocked at 5 kills.
            if (stage >= 2) {
                applyEffects(target, cfg.getConfigurationSection("target-effects"));
            }
        }

        // Stage 3: wielder buffs, unlocked at 10 kills.
        if (stage >= 3) {
            applyEffects(player, cfg.getConfigurationSection("self-effects"));
        }

        long cooldownMillis = (long) (cfg.getDouble("cooldown-seconds", 60.0) * 1000.0);
        getConfig().set("cooldowns." + player.getUniqueId(), now + cooldownMillis);
        saveConfig();
    }

    private LivingEntity findTarget(Player player) {
        FileConfiguration cfg = getConfig();
        double range = cfg.getDouble("range", 30.0);
        boolean hitPlayers = cfg.getBoolean("affect-players", true);
        boolean hitMobs = cfg.getBoolean("affect-mobs", true);

        Location eye = player.getEyeLocation();
        RayTraceResult result = player.getWorld().rayTrace(
                eye, eye.getDirection(), range, FluidCollisionMode.NEVER,
                true, 0.3, entity -> {
                    if (entity.equals(player) || !(entity instanceof LivingEntity)) return false;
                    if (entity instanceof ArmorStand) return false;
                    if (entity instanceof Player other) {
                        return hitPlayers && other.getGameMode() != GameMode.SPECTATOR;
                    }
                    return hitMobs;
                });

        if (result == null) return null;
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
            if (damage > 0) target.damage(damage, caster);
        }
    }

    private void applyEffects(LivingEntity entity, ConfigurationSection section) {
        if (section == null) return;

        for (String name : section.getKeys(false)) {
            ConfigurationSection effect = section.getConfigurationSection(name);
            if (effect == null) continue;

            PotionEffectType type;
            try {
                type = PotionEffectType.getByKey(
                        NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                type = null;
            }

            if (type == null) {
                getLogger().warning("Unknown potion effect in config: " + name);
                continue;
            }

            int ticks = (int) Math.round(
                    effect.getDouble("duration-seconds", 10.0) * 20.0);
            int amplifier = Math.max(1, effect.getInt("level", 1)) - 1;
            if (ticks <= 0) continue;

            entity.addPotionEffect(new PotionEffect(
                    type, ticks, amplifier,
                    effect.getBoolean("ambient", false),
                    effect.getBoolean("particles", true),
                    effect.getBoolean("icon", true)));
        }
    }

    private Component message(String path, String fallback, TagResolver... resolvers) {
        return MM.deserialize(getConfig().getString(path, fallback), resolvers);
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /stormwand <give [player]|reload>"));
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

                Player target;
                if (args.length > 1) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else if (sender instanceof Player self) {
                    target = self;
                } else {
                    target = null;
                }

                if (target == null) {
                    sender.sendMessage(Component.text(
                            "Player not found. Usage: /stormwand give <player>"));
                    return true;
                }

                int stage = getStage(getKills(target));
                Map<Integer, ItemStack> leftover =
                        target.getInventory().addItem(createWand(stage));
                for (ItemStack extra : leftover.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), extra);
                }
                sender.sendMessage(Component.text(
                        "Gave the Storm Wand to " + target.getName() + "."));
            }
            default -> sender.sendMessage(Component.text(
                    "Usage: /stormwand <give [player]|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return List.of("give", "reload").stream()
                    .filter(s -> s.startsWith(typed)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .toList();
        }
        return List.of();
    }
}
