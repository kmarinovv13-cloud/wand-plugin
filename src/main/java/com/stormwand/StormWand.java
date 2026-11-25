package com.stormwand;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class StormWand extends JavaPlugin implements Listener {

    private static final NamespacedKey WAND_KEY = new NamespacedKey("stormwand", "storm_wand");
    private static final NamespacedKey STAGE_KEY = new NamespacedKey("stormwand", "stage");

    private final Map<UUID, Set<UUID>> uniqueVictims = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private File killsFile;
    private org.bukkit.configuration.file.YamlConfiguration killsConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("stormwand") != null) {
            getCommand("stormwand").setExecutor(this);
        } else {
            getLogger().severe("Command 'stormwand' is missing from plugin.yml.");
        }

        loadKills();
        getLogger().info("StormWand enabled.");
    }

    @Override
    public void onDisable() {
        saveKills();
        getLogger().info("StormWand disabled.");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) {
            return;
        }

        Set<UUID> victims = uniqueVictims.computeIfAbsent(killer.getUniqueId(), ignored -> new HashSet<>());
        int oldStage = getStage(victims.size());

        if (victims.add(victim.getUniqueId())) {
            saveKills();

            int newStage = getStage(victims.size());

            if (newStage > oldStage) {
                updateWands(killer);
                killer.sendMessage(colorMessage(getConfig().getString(
                        "messages.unlocked-stage-" + newStage,
                        "&bYour Storm Wand has advanced to Stage " + newStage + "!"
                )));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(colorMessage("&bStormWand commands:"));
            sender.sendMessage(colorMessage("&7/stormwand give <player>"));
            sender.sendMessage(colorMessage("&7/stormwand kills [player]"));
            sender.sendMessage(colorMessage("&7/stormwand reset <player>"));
            sender.sendMessage(colorMessage("&7/stormwand setkills <player> <number>"));
            sender.sendMessage(colorMessage("&7/stormwand reload"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("stormwand.reload")) {
                    sender.sendMessage(colorMessage("&cYou do not have permission."));
                    return true;
                }
                reloadConfig();
                loadKills();
                updateAllOnlineWands();
                sender.sendMessage(colorMessage("&aStormWand configuration reloaded."));
                return true;

            case "give":
                if (!sender.hasPermission("stormwand.give")) {
                    sender.sendMessage(colorMessage("&cYou do not have permission."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(colorMessage("&cUsage: /stormwand give <player>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(colorMessage("&cThat player must be online."));
                    return true;
                }
                Map<Integer, ItemStack> leftover = target.getInventory().addItem(createWand(target));
                for (ItemStack item : leftover.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), item);
                }
                sender.sendMessage(colorMessage("&aGave a Storm Wand to " + target.getName() + "."));
                target.sendMessage(colorMessage("&bYou received a Storm Wand!"));
                return true;

            case "kills":
                Player checkPlayer;
                if (args.length >= 2) {
                    if (!sender.hasPermission("stormwand.admin")) {
                        sender.sendMessage(colorMessage("&cYou do not have permission."));
                        return true;
                    }
                    checkPlayer = Bukkit.getPlayerExact(args[1]);
                    if (checkPlayer == null) {
                        sender.sendMessage(colorMessage("&cThat player must be online."));
                        return true;
                    }
                } else if (sender instanceof Player playerSender) {
                    checkPlayer = playerSender;
                } else {
                    sender.sendMessage(colorMessage("&cUsage: /stormwand kills <player>"));
                    return true;
                }
                int killCount = getUniqueKillCount(checkPlayer);
                sender.sendMessage(colorMessage("&7" + checkPlayer.getName() + "&f has &a" + killCount + "&7 unique kill(s)."));
                return true;

            case "reset":
                if (!sender.hasPermission("stormwand.admin")) {
                    sender.sendMessage(colorMessage("&cYou do not have permission."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(colorMessage("&cUsage: /stormwand reset <player>"));
                    return true;
                }
                Player resetPlayer = Bukkit.getPlayerExact(args[1]);
                if (resetPlayer == null) {
                    sender.sendMessage(colorMessage("&cThat player must be online."));
                    return true;
                }
                uniqueVictims.remove(resetPlayer.getUniqueId());
                saveKills();
                updateWands(resetPlayer);
                sender.sendMessage(colorMessage("&aReset kills for " + resetPlayer.getName() + "."));
                return true;

            case "setkills":
                if (!sender.hasPermission("stormwand.admin")) {
                    sender.sendMessage(colorMessage("&cYou do not have permission."));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(colorMessage("&cUsage: /stormwand setkills <player> <number>"));
                    return true;
                }
                Player setPlayer = Bukkit.getPlayerExact(args[1]);
                if (setPlayer == null) {
                    sender.sendMessage(colorMessage("&cThat player must be online."));
                    return true;
                }
                try {
                    int count = Integer.parseInt(args[2]);
                    Set<UUID> dummy = uniqueVictims.getOrDefault(setPlayer.getUniqueId(), new HashSet<>());
                    dummy.clear();
                    for (int i = 0; i < count; i++) {
                        dummy.add(UUID.randomUUID());
                    }
                    uniqueVictims.put(setPlayer.getUniqueId(), dummy);
                    saveKills();
                    updateWands(setPlayer);
                    sender.sendMessage(colorMessage("&aSet kills for " + setPlayer.getName() + " to " + count + "."));
                } catch (NumberFormatException e) {
                    sender.sendMessage(colorMessage("&cInvalid number."));
                }
                return true;

            default:
                sender.sendMessage(colorMessage("&cUnknown subcommand."));
                return true;
        }
    }

    private ItemStack createWand(Player owner) {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(WAND_KEY, PersistentDataType.BYTE, (byte) 1);
        pdc.set(STAGE_KEY, PersistentDataType.INTEGER, getStage(getUniqueKillCount(owner)));

        applyStageModel(meta, getStage(getUniqueKillCount(owner)));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        Byte marker = pdc.get(WAND_KEY, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void updateWands(Player player) {
        ItemMeta meta = null;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (isWand(item)) {
            meta = item.getItemMeta();
            if (meta != null) {
                applyStageModel(meta, getStage(getUniqueKillCount(player)));
                item.setItemMeta(meta);
            }
        }

        item = player.getInventory().getItemInOffHand();
        if (isWand(item)) {
            meta = item.getItemMeta();
            if (meta != null) {
                applyStageModel(meta, getStage(getUniqueKillCount(player)));
                item.setItemMeta(meta);
            }
        }
    }

    private void updateAllOnlineWands() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateWands(player);
        }
    }

    private void applyStageModel(ItemMeta meta, int stage) {
        String prefix = "wand.stages.stage-" + stage;

        String name = getConfig().getString(prefix + ".name");
        if (name != null && !name.isBlank()) {
            meta.displayName(MiniMessage.miniMessage().parse(name));
        }

        List<String> loreList = getConfig().getStringList(prefix + ".lore");
        if (!loreList.isEmpty()) {
            List<Component> lore = loreList.stream()
                    .filter(s -> !s.isBlank())
                    .map(MiniMessage.miniMessage()::parse)
                    .collect(Collectors.toList());
            meta.lore(lore);
        }

        boolean glow = getConfig().getBoolean(prefix + ".glow", true);
        meta.setGlowing(glow);

        int modelData = getConfig().getInt(prefix + ".custom-model-data", 0);
        if (modelData > 0) {
            meta.setCustomModelData(modelData);
        }

        String itemModel = getConfig().getString(prefix + ".item-model", "");
        if (!itemModel.isBlank()) {
            try {
                NamespacedKey modelKey = NamespacedKey.fromString(itemModel);
                if (modelKey != null) {
                    meta.setItemModel(modelKey);
                }
            } catch (Exception e) {
                getLogger().warning("Invalid item-model key: " + itemModel);
            }
        }
    }

    private int getUniqueKillCount(Player player) {
        return uniqueVictims.getOrDefault(player.getUniqueId(), Set.of()).size();
    }

    private int getStage(int uniqueKills) {
        int killsForDebuffs = getConfig().getInt("progression.kills-for-debuffs", 1);
        int killsForBuffs = getConfig().getInt("progression.kills-for-buffs", 3);

        if (uniqueKills >= killsForBuffs) return 3;
        if (uniqueKills >= killsForDebuffs) return 2;
        return 1;
    }

    @EventHandler
    public void onWandUse(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !isWand(item)) {
            return;
        }

        event.setCancelled(true);
        cast(event.getPlayer());
    }

    private void cast(Player player) {
        if (!player.hasPermission("stormwand.use")) {
            player.sendMessage(colorMessage("&cYou do not have permission to use this wand."));
            return;
        }

        long cooldownSeconds = getConfig().getLong("cooldown-seconds", 60);
        long cooldownMillis = cooldownSeconds * 1000L;
        long readyAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        long now = System.currentTimeMillis();

        if (now < readyAt) {
            long secondsLeft = (readyAt - now + 999L) / 1000L;
            player.sendMessage(colorMessage(getConfig().getString(
                    "messages.on-cooldown",
                    "&cThe wand is cooling down for " + secondsLeft + " more seconds."
            ).replace("<seconds>", String.valueOf(secondsLeft))));
            return;
        }

        double range = getConfig().getDouble("range-blocks", 30.0);
        LivingEntity target = findTarget(player, range);

        if (target == null) {
            boolean requireTarget = getConfig().getBoolean("require-target", true);
            if (requireTarget) {
                player.sendMessage(colorMessage(getConfig().getString(
                        "messages.no-target",
                        "&cLook at a living target within " + (int) range + " blocks."
                )));
                return;
            }
            // Even with no target, stage 3 buffs can still apply if config allows
            int stage = getStage(getUniqueKillCount(player));
            if (stage >= 3) {
                applyWielderBuffs(player);
                cooldowns.put(player.getUniqueId(), now + cooldownMillis);
                player.sendMessage(colorMessage("&bStorm Wand cast!"));
            }
            return;
        }

        int stage = getStage(getUniqueKillCount(player));
        Location targetLocation = target.getLocation();

        boolean realLightning = getConfig().getBoolean("lightning.real-lightning", false);
        if (realLightning) {
            target.getWorld().strikeLightning(targetLocation);
        } else {
            target.getWorld().strikeLightningEffect(targetLocation);
        }

        double customDamage = getConfig().getDouble("lightning.damage", 4.0);
        if (!realLightning && customDamage > 0.0) {
            target.damage(customDamage, player);
        }

        if (stage >= 2) {
            applyTargetDebuffs(target);
        }

        if (stage >= 3) {
            applyWielderBuffs(player);
        }

        cooldowns.put(player.getUniqueId(), now + cooldownMillis);
        player.sendMessage(colorMessage("&bStorm Wand cast!"));
    }

    private LivingEntity findTarget(Player player, double range) {
        org.bukkit.Location eyeLocation = player.getEyeLocation();
        org.bukkit.util.Vector direction = eyeLocation.getDirection();

        RayTraceResult result = player.getWorld().rayTraceEntities(
                eyeLocation,
                direction,
                range,
                0.3,
                entity -> entity instanceof LivingEntity && entity != player
        );

        if (result == null || !(result.getHitEntity() instanceof LivingEntity target)) {
            return null;
        }
        return target;
    }

    private void applyTargetDebuffs(LivingEntity target) {
        int durationSeconds = getConfig().getInt("target-effects.slowness.duration-seconds", 10);
        int slownessLevel = getConfig().getInt("target-effects.slowness.level", 4);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationSeconds * 20, slownessLevel - 1, false, false, true));

        durationSeconds = getConfig().getInt("target-effects.blindness.duration-seconds", 10);
        int blindnessLevel = getConfig().getInt("target-effects.blindness.level", 1);
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, durationSeconds * 20, blindnessLevel - 1, false, false, true));
    }

    private void applyWielderBuffs(Player player) {
        int durationSeconds = getConfig().getInt("self-effects.speed.duration-seconds", 10);
        int speedLevel = getConfig().getInt("self-effects.speed.level", 3);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, speedLevel - 1, false, false, true));

        durationSeconds = getConfig().getInt("self-effects.strength.duration-seconds", 10);
        int strengthLevel = getConfig().getInt("self-effects.strength.level", 3);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, durationSeconds * 20, strengthLevel - 1, false, false, true));
    }

    private void loadKills() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
            return;
        }

        killsFile = new File(dataFolder, "kills.yml");
        killsConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(killsFile);

        for (String playerId : killsConfig.getKeys(false)) {
            try {
                UUID killerId = UUID.fromString(playerId);
                Set<UUID> victims = new HashSet<>();
                for (String victimId : killsConfig.getStringList(playerId)) {
                    try {
                        victims.add(UUID.fromString(victimId));
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Ignoring invalid victim UUID in kills.yml.");
                    }
                }
                uniqueVictims.put(killerId, victims);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Ignoring invalid player UUID in kills.yml.");
            }
        }
    }

    private void saveKills() {
        if (killsConfig == null || killsFile == null) return;

        for (Map.Entry<UUID, Set<UUID>> entry : uniqueVictims.entrySet()) {
            List<String> victimIds = entry.getValue().stream()
                    .map(UUID::toString)
                    .sorted()
                    .collect(Collectors.toList());
            killsConfig.set(entry.getKey().toString(), victimIds);
        }

        try {
            killsConfig.save(killsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save kills.yml: " + e.getMessage());
        }
    }

    private String colorMessage(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
