package ict.minesunshineone.peek;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 处理/peek命令的执行器类
 */
public class PeekCommand implements CommandExecutor, TabCompleter {

    private final PeekPlugin plugin;
    private final Map<Player, PeekData> peekingPlayers = new HashMap<>();  // 存储正在观察中的玩家数据
    private final Map<Player, ScheduledTask> peekTimers = new HashMap<>();

    public PeekCommand(PeekPlugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("插件实例不能为空");
        }
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        long startTime = System.currentTimeMillis();

        try {
            if (!(sender instanceof Player)) {
                sendMessage(sender, "command-player-only");
                return true;
            }

            Player player = (Player) sender;

            // 检查权限
            if (!player.hasPermission("peek.use")) {
                sendMessage(player, "no-permission");
                return true;
            }

            // 检查参数
            if (args.length == 0) {
                sendMessage(player, "usage");
                return true;
            }

            // 处理退出命令
            if (args[0].equalsIgnoreCase("exit")) {
                return handleExit(player);
            }

            // 处理统计命令
            if (args[0].equalsIgnoreCase("stats")) {
                return handleStats(player);
            }

            // 处理观察命令
            if (args[0].equalsIgnoreCase("privacy")) {
                if (!(sender instanceof Player)) {
                    sendMessage(sender, "command-player-only");
                    return true;
                }
                plugin.getPrivacyManager().togglePrivateMode((Player) sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("accept")) {
                if (!(sender instanceof Player)) {
                    sendMessage(sender, "command-player-only");
                    return true;
                }
                plugin.getPrivacyManager().handleAccept((Player) sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("deny")) {
                if (!(sender instanceof Player)) {
                    sendMessage(sender, "command-player-only");
                    return true;
                }
                plugin.getPrivacyManager().handleDeny((Player) sender);
                return true;
            }

            return handlePeek(player, args[0]);
        } catch (Exception e) {
            plugin.getLogger().severe(String.format("执行peek命令时发生错误: %s", e.getMessage()));
            sendMessage(sender, "command-error");
        } finally {
            if (plugin.isDebugEnabled()) {
                long duration = System.currentTimeMillis() - startTime;
                plugin.getLogger().info(String.format("命令执行耗时: %dms", duration));
            }
        }
        return true;
    }

    /**
     * 处理玩家开始观察的逻辑
     *
     * @param player 执行命令的玩家
     * @param targetName 目标玩家名
     * @return 是否执行成功
     */
    public boolean handlePeek(Player player, String targetName) {
        // 检查玩家是否已经在偷窥中
        if (peekingPlayers.containsKey(player)) {
            sendMessage(player, "already-peeking");
            return true;
        }

        // 获取目标玩家
        Player target = plugin.getServer().getPlayer(targetName);
        if (target == null) {
            sendMessage(player, "player-not-found", "player", targetName);
            return true;
        }

        // 不能偷窥自己
        if (target == player) {
            sendMessage(player, "cannot-peek-self");
            return true;
        }

        // 检查目标玩家是否正在peek状态
        if (peekingPlayers.containsKey(target)) {
            sendMessage(player, "target-is-peeking");
            return true;
        }

        // 检查私人模式
        if (plugin.getPrivacyManager().isPrivateMode(target)) {
            plugin.getPrivacyManager().sendPeekRequest(player, target);
            return true;
        }

        // 记录观察开始
        if (plugin.getStatistics() != null) {
            plugin.getStatistics().recordPeekStart(player, target);
        }

        // 存储开始时间和数据
        long startTime = System.currentTimeMillis();
        PeekData peekData = new PeekData(
                player.getLocation().clone(),
                player.getGameMode(),
                target,
                startTime
        );
        peekingPlayers.put(player, peekData);

        // 立即保存状态，以防意外情况
        plugin.getOfflinePeekManager().saveOfflinePlayerState(player, peekData);

        // 在目标玩家所在区域执行传送
        plugin.getServer().getRegionScheduler().execute(plugin, target.getLocation(), () -> {
            if (!player.getWorld().equals(target.getWorld())) {
                // 跨维度传送，先传送再切换模式
                player.getScheduler().run(plugin, scheduledTask -> {
                    player.teleportAsync(target.getLocation()).thenAccept(result -> {
                        if (result) {
                            player.setGameMode(GameMode.SPECTATOR);
                            handlePeekSuccess(player, target, true);
                        } else {
                            handlePeekFail(player);
                            peekingPlayers.remove(player);
                        }
                    });
                }, () -> {
                });
            } else {
                // 同维度传送
                player.getScheduler().run(plugin, scheduledTask -> {
                    player.setGameMode(GameMode.SPECTATOR);
                    player.teleportAsync(target.getLocation()).thenAccept(result -> {
                        if (result) {
                            handlePeekSuccess(player, target, result);
                        } else {
                            handlePeekFail(player);
                            player.setGameMode(peekData.getOriginalGameMode());
                            peekingPlayers.remove(player);
                        }
                    });
                }, () -> {
                });
            }
        });

        // 设置最大观察时间定时器
        if (plugin.getMaxPeekDuration() > 0) {
            long durationInMillis = plugin.getMaxPeekDuration() * 1000L;
            ScheduledTask task = plugin.getServer().getAsyncScheduler().runDelayed(plugin, scheduledTask -> {
                if (peekingPlayers.containsKey(player)) {
                    plugin.getServer().getRegionScheduler().execute(plugin,
                            player.getLocation(), () -> {
                        handleExit(player);
                        sendMessage(player, "time-expired");
                    });
                }
            }, durationInMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            peekTimers.put(player, task);
        }

        return true;
    }

    /**
     * 处理玩家退出观察的逻辑
     *
     * @param player 要退出观察的玩家
     * @return 是否执行成功
     */
    public boolean handleExit(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        PeekData data = peekingPlayers.get(player);
        if (data == null) {
            sendMessage(player, "not-peeking");
            return true;
        }

        // 防止重复退出
        if (data.isExiting()) {
            return true;
        }

        data.setExiting(true);
        Player target = data.getTargetPlayer();

        // 只有在主动退出时才删除离线状态数据
        if (player.isOnline()) {  // 确保是主动退出
            String uuidString = player.getUniqueId().toString();
            plugin.getOfflinePeekManager().getPendingPeeks().set(uuidString, null);
            try {
                plugin.getOfflinePeekManager().getPendingPeeks().save(plugin.getOfflinePeekManager().getPendingPeeksFile());
            } catch (IOException e) {
                plugin.getLogger().warning(String.format("无法删除玩家 %s 的离线观察状态: %s",
                        player.getName(), e.getMessage()));
            }
        }

        restorePlayerState(player, data);
        peekingPlayers.remove(player);

        sendMessage(player, "peek-end");
        if (target != null && target.isOnline()) {
            sendMessage(target, "peek-end-target", "player", player.getName());
            playSound(target, "end-peek");
        }

        updateActionBar(target);

        if (plugin.getStatistics() != null) {
            long duration = (System.currentTimeMillis() - data.getStartTime()) / 1000;
            plugin.getStatistics().recordPeekDuration(player, duration);
        }

        // 在退出时设置冷却
        plugin.getCooldownManager().setCooldownAfterPeek(player);

        cancelPeekTimer(player);

        return true;
    }

    /**
     * 更新目标玩家的观察者数量显示
     */
    private void updateActionBar(Player target) {
        long count = peekingPlayers.values().stream()
                .filter(data -> data.getTargetPlayer().equals(target))
                .count();

        if (count > 0) {
            String message = plugin.getMessages().get("action-bar", "count", String.valueOf(count));
            if (message != null) {
                target.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize(message));
            }
        }
    }

    /**
     * 获取当前所有正在观察的玩家
     *
     * @return 观察中的玩家Map
     */
    public Map<Player, PeekData> getPeekingPlayers() {
        return peekingPlayers;
    }

    /**
     * 处理查看统计信息的命令
     */
    private boolean handleStats(Player player) {
        if (plugin.getStatistics() == null) {
            sendMessage(player, "stats-disabled");
            return true;
        }

        Statistics.PlayerStats stats = plugin.getStatistics().getStats(player);
        sendMessage(player, "stats-self",
                "peek_count", String.valueOf(stats.getPeekCount()),
                "peeked_count", String.valueOf(stats.getPeekedCount()),
                "peek_duration", String.format("%.1f", stats.getPeekDuration() / 60.0)
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player)) {
            return null;
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.add("exit");
            completions.add("stats");
            completions.add("privacy");
            completions.add("accept");
            completions.add("deny");
            completions.addAll(plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList()));

            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return null;
    }

    private void restorePlayerState(Player player, PeekData data) {
        if (player == null || data == null || !player.isOnline()) {
            return;
        }

        plugin.getServer().getRegionScheduler().execute(plugin, data.getOriginalLocation(), () -> {
            if (!player.isOnline()) {
                return;  // 再次检查，以防异步执行期间玩家下线
            }
            if (!player.getWorld().equals(data.getOriginalLocation().getWorld())) {
                // 跨维度返回，先传送再切换模式
                player.getScheduler().run(plugin, scheduledTask -> {
                    player.teleportAsync(data.getOriginalLocation()).thenAccept(result -> {
                        if (result) {
                            player.setGameMode(data.getOriginalGameMode());
                        } else {
                            // 只有在传送真正失败时才记录日志
                            plugin.getLogger().warning(String.format(
                                    "玩家 %s 跨维度返回失败，目标位置: world=%s, x=%.2f, y=%.2f, z=%.2f",
                                    player.getName(),
                                    data.getOriginalLocation().getWorld().getName(),
                                    data.getOriginalLocation().getX(),
                                    data.getOriginalLocation().getY(),
                                    data.getOriginalLocation().getZ()
                            ));
                        }
                    });
                }, () -> {
                });
            } else {
                // 同维度返回
                player.getScheduler().run(plugin, scheduledTask -> {
                    player.setGameMode(data.getOriginalGameMode());
                    player.teleportAsync(data.getOriginalLocation());
                }, () -> {
                });
            }
        });
    }

    private void playSound(Player player, String soundKey) {
        if (player != null) {
            player.playSound(player.getLocation(),
                    Sound.valueOf(plugin.getConfig().getString("sounds." + soundKey, "BLOCK_NOTE_BLOCK_PLING")),
                    1.0f, 1.0f);
        }
    }

    void sendMessage(CommandSender sender, String key, String... replacements) {
        if (sender == null || key == null) {
            return;
        }

        String message = plugin.getMessages().get(key, replacements);
        if (message == null) {
            return;
        }

        if (sender instanceof Player player) {
            player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().deserialize(message));
        } else {
            sender.sendMessage(message);
        }
    }

    private void handlePeekSuccess(Player player, Player target, boolean result) {
        if (result) {
            sendMessage(player, "peek-start", "player", target.getName());
            sendMessage(target, "being-peeked", "player", player.getName());
            playSound(target, "start-peek");
            updateActionBar(target);
        } else {
            handlePeekFail(player);
        }
    }

    private void handlePeekFail(Player player) {
        peekingPlayers.remove(player);
        sendMessage(player, "teleport-failed");
        plugin.getLogger().warning(String.format("玩家 %s 传送失败", player.getName()));
    }

    private void cancelPeekTimer(Player player) {
        ScheduledTask task = peekTimers.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    public Map<Player, ScheduledTask> getPeekTimers() {
        return peekTimers;
    }
}
