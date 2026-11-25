package com.stormwand;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StormWand extends JavaPlugin implements Listener {
    private final Map<UUID, Set<UUID>> uniqueVictims = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private NamespacedKey wandKey;
    private File killsFile;
    private org.bukkit.configuration.file.YamlConfiguration killsConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        wandKey = new NamespacedKey(this, "storm_wand");
        loadKills();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("stormwand") != null) {
            getCommand("stormwand").setExecutor(this);
        }
    }

    @Override
    public void onDisable() {
        saveKills();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        Set<UUID> victims = uniqueVictims.computeIfAbsent(
                killer.getUniqueId(), ignored -> new HashSet<>()
        );

        if (victims.add(victim.getUniqueId())) {
            saveKills();

            int oldStage = getStage(victims.size() - 1);
            int newStage = getStage(victims.size());

            if (newStage > oldStage) {
                updateWands(killer);
                killer.sendMessage(color(
                        "&bYour Storm Wand has advanced to Stage " + newStage + "!"
                ));
            }
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("stormwand.reload")) {
                sender.sendMessage(color("&cYou do not have permission."));
                return true;
            }

            reloadConfig();
            updateAllOnlineWands();
            sender.sendMessage(color("&aStormWand configuration reloaded."));
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("stormwand.give")) {
                sender.sendMessage(color("&cYou do not have permission."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(color("&cUsage: /stormwand give <player>"));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(color("&cThat player must be online."));
                return true;
            }

            target.getInventory().addItem(createWand(target));
            sender.sendMessage(color("&aGave a Storm Wand to " + target.getName() + "."));
            target.sendMessage(color("&bYou received a Storm Wand!"));
            return true;
        }

        sendUsage(sender);
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(color("&bStormWand commands:"));
        sender.sendMessage(color("&7/stormwand give <player>"));
        sender.sendMessage(color("&7/stormwand reload"));
    }

    private ItemStack createWand(Player owner) {
        ItemStack item = new ItemStack(org.bukkit.Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(getConfig().getString(
                "wand.name", "&b&lStorm Wand"
        )));
        meta.getPersistentDataContainer().set(
                wandKey, PersistentDataType.BYTE, (byte) 1
        );

        int stage = getStage(getUniqueKillCount(owner));
        applyStageModel(meta, stage);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        Byte marker = item.getItemMeta()
                .getPersistentDataContainer()
                .get(wandKey, PersistentDataType.BYTE);

        return marker != null && marker == (byte) 1;
    }

    private void updateWands(Player player) {
        ItemStack[] contents = player.getInventory().getContents();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isWand(item)) {
                continue;
            }

            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }

            applyStageModel(meta, getStage(getUniqueKillCount(player)));
            item.setItemMeta(meta);
        }

        player.getInventory().setContents(contents);
    }

    private void updateAllOnlineWands() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateWands(player);
        }
    }

    private void applyStageModel(ItemMeta meta, int stage) {
        String type = getConfig().getString(
                "models.stage-" + stage + ".type", "custom-model-data"
        );

        if ("item-model".equalsIgnoreCase(type)) {
            String model = getConfig().getString(
                    "models.stage-" + stage + ".value", ""
            );

            if (!model.isBlank()) {
                NamespacedKey key = NamespacedKey.fromString(model);
                if (key != null) {
                    meta.setItemModel(key);
                }
            }
        } else {
            int modelData = getConfig().getInt(
                    "models.stage-" + stage + ".value", 0
            );
            if (modelData > 0) {
                meta.setCustomModelData(modelData);
            }
        }
    }

    private int getUniqueKillCount(Player player) {
        return uniqueVictims.getOrDefault(
                player.getUniqueId(), Set.of()
        ).size();
    }

    private int getStage(int uniqueKills) {
        if (uniqueKills >= getConfig().getInt("progression.stage-3-kills", 3)) {
            return 3;
        }
        if (uniqueKills >= getConfig().getInt("progression.stage-2-kills", 1)) {
            return 2;
        }
        return 1;
    }

    @EventHandler
    public void onWandUse(org.bukkit.event.player.PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!isWand(item)) {
            return;
        }

        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                event.setCancelled(true);
                cast(player);
            }
            default -> {
            }
        }
    }

    private void cast(Player player) {
        if (!player.hasPermission("stormwand.use")) {
            player.sendMessage(color("&cYou do not have permission to use this wand."));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = getConfig().getLong("cooldown-seconds", 60) * 1000L;
        long readyAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);

        if (now < readyAt) {
            long secondsLeft = (readyAt - now + 999) / 1000;
            player.sendMessage(color("&cThe wand is cooling down for "
                    + secondsLeft + " more seconds."));
            return;
        }

        int stage = getStage(getUniqueKillCount(player));
        double range = getConfig().getDouble("range-blocks", 30.0);
        LivingEntity target = findTarget(player, range);

        if (target == null) {
            player.sendMessage(color("&cLook at a living target within "
                    + (int) range + " blocks."));
            return;
        }

        cooldowns.put(player.getUniqueId(), now + cooldownMillis);

        Location targetLocation = target.getLocation();
        boolean damageEnabled = getConfig().getBoolean(
                "lightning.damage-enabled", false
        );

        if (damageEnabled) {
            target.getWorld().strikeLightning(targetLocation);
        } else {
            target.getWorld().strikeLightningEffect(targetLocation);
        }

        double damage = getConfig().getDouble("lightning.damage", 4.0);
        if (damageEnabled && damage > 0.0) {
            target.damage(damage, player);
        }

        if (stage >= 2) {
            applyTargetDebuffs(target);
        }

        if (stage >= 3) {
            applyWielderBuffs(player);
        }

        player.sendMessage(color("&bStorm Wand cast!"));
    }

    private LivingEntity findTarget(Player player, double range) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        RayTraceResult result = player.getWorld().rayTraceEntities(
                eye,
                direction,
                range,
                0.3,
                entity -> entity instanceof LivingEntity
                        && entity != player
        );

        if (result == null || !(result.getHitEntity() instanceof LivingEntity living)) {
            return null;
        }

        return living;
    }

    private void applyTargetDebuffs(LivingEntity target) {
        int duration = getConfig().getInt("target-debuffs.duration-seconds", 10) * 20;

        applyConfiguredEffect(
                target,
                "target-debuffs.slowness",
                duration
        );
        applyConfiguredEffect(
                target,
                "target-debuffs.blindness",
                duration
        );
    }

    private void applyWielderBuffs(Player player) {
        int duration = getConfig().getInt("wielder-buffs.duration-seconds", 10) * 20;

        applyConfiguredEffect(player, "wielder-buffs.speed", duration);
        applyConfiguredEffect(player, "wielder-buffs.strength", duration);
    }

    private void applyConfiguredEffect(
            LivingEntity entity,
            String path,
            int durationTicks
    ) {
        String effectName = getConfig().getString(path + ".effect", "");
        int amplifier = Math.max(0, getConfig().getInt(path + ".amplifier", 0));

        PotionEffectType type = PotionEffectType.getByName(
                effectName.toUpperCase()
        );

        if (type != null) {
            entity.addPotionEffect(new PotionEffect(
                    type,
                    durationTicks,
                    amplifier,
                    true,
                    true,
                    true
            ));
        } else {
            getLogger().warning("Unknown potion effect at config path " + path);
        }
    }

    private void loadKills() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }

        killsFile = new File(getDataFolder(), "kills.yml");
        if (!killsFile.exists()) {
            try {
                if (!killsFile.createNewFile()) {
                    getLogger().warning("Could not create kills.yml.");
                }
            } catch (IOException exception) {
                getLogger().severe("Could not create kills.yml: "
                        + exception.getMessage());
            }
        }

        killsConfig = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(killsFile);

        for (String playerId : killsConfig.getKeys(false)) {
            try {
                UUID killerId = UUID.fromString(playerId);
                Set<UUID> victims = new HashSet<>();

                List<String> savedVictims = killsConfig.getStringList(playerId);
                for (String victimId : savedVictims) {
                    try {
                        victims.add(UUID.fromString(victimId));
                    } catch (IllegalArgumentException ignored) {
                        getLogger().warning("Ignoring invalid victim UUID in kills.yml.");
                    }
                }

                uniqueVictims.put(killerId, victims);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Ignoring invalid player UUID in kills.yml.");
            }
        }
    }

    private void saveKills() {
        if (killsConfig == null || killsFile == null) {
            return;
        }

        killsConfig.getKeys(false).forEach(killsConfig::set);

        for (Map.Entry<UUID, Set<UUID>> entry : uniqueVictims.entrySet()) {
            List<String> victims = entry.getValue().stream()
                    .map(UUID::toString)
                    .sorted()
                    .toList();
            killsConfig.set(entry.getKey().toString(), victims);
        }

        try {
            killsConfig.save(killsFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save kills.yml: "
                    + exception.getMessage());
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
