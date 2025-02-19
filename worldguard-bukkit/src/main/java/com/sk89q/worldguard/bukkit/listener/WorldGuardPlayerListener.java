/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit.listener;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.world.gamemode.GameMode;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.event.player.ProcessPlayerEvent;
import com.sk89q.worldguard.bukkit.util.Events;
import com.sk89q.worldguard.bukkit.util.Materials;
import com.sk89q.worldguard.config.ConfigurationManager;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.handler.GameModeFlag;
import com.sk89q.worldguard.util.Entities;
import com.sk89q.worldguard.util.command.CommandFilter;
import com.sk89q.worldguard.util.profile.Profile;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nullable;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.sk89q.worldguard.commands.WorldGuardCommands.buildnumber;

/**
 * Handles all events thrown in relation to a player.
 */
public class WorldGuardPlayerListener extends AbstractListener {

    private static final Logger log = Logger.getLogger(WorldGuardPlayerListener.class.getCanonicalName());
    private static final Pattern opPattern = Pattern.compile("^/(?:minecraft:)?(?:bukkit:)?(?:de)?op(?:\\s.*)?$", Pattern.CASE_INSENSITIVE);

    public WorldGuardPlayerListener(WorldGuardPlugin plugin) {
        super(plugin);
    }


    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());
        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().getIfPresent(localPlayer);
        if (session != null) {
            GameModeFlag handler = session.getHandler(GameModeFlag.class);
            if (handler != null && wcfg.useRegions && !WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer,
                    localPlayer.getWorld())) {
                GameMode expected = handler.getSetGameMode();
                if (handler.getOriginalGameMode() != null && expected != null && expected != BukkitAdapter.adapt(event.getNewGameMode())) {
                    log.info("Herní mód hráče " + player.getName() + " byl změněn z důvodu jiného herního módu v regionu!");
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        ConfigurationManager cfg = getConfig();
        WorldConfiguration wcfg = getWorldConfig(world);

        if (cfg.activityHaltToggle) {
            player.sendMessage(ChatColor.YELLOW
                    + "Intenzivní aktivita serveru byla POZASTAVENA.");

            int removed = 0;

            for (Entity entity : world.getEntities()) {
                if (Entities.isIntensiveEntity(BukkitAdapter.adapt(entity))) {
                    entity.remove();
                    removed++;
                }
            }

            if (removed > 10) {
                log.info("Halt-Act: " + removed + " bytostí (>10) bylo odebráno ze světa "
                        + player.getWorld());
            }
        }

        if (wcfg.fireSpreadDisableToggle) {
            player.sendMessage(ChatColor.YELLOW
                    + "Šíření ohně je v tomto světě globálně zakázáno.");
        }

        Events.fire(new ProcessPlayerEvent(player));
        WorldGuard.getInstance().getExecutorService().submit(() ->
            WorldGuard.getInstance().getProfileCache().put(new Profile(player.getUniqueId(), player.getName())));

    //Kontrola verze překladu WorldGuardu

        if (player.hasPermission("worldguard.update")) {
            try {
                String giturl = "http://jenkins.valleycube.cz/job/WorldGuard-CZ-preklad-master/ws/build.number";
                URL url = new URL(giturl);
                URLConnection con = url.openConnection();
                Pattern p = Pattern.compile("text/html;\\s+charset=([^\\s]+)\\s*");
                Matcher m = p.matcher(con.getContentType());

                String charset = m.matches() ? m.group(1) : "UTF-8";
                Reader r = new InputStreamReader(con.getInputStream(), charset);
                StringBuilder buf = new StringBuilder();

                while (true) {
                    int ch = r.read();
                    if (ch < 0)
                        break;
                    buf.append((char) ch);
                }
                String str = buf.toString();

                File cacheDir = new File("plugins/WorldGuard", "cache");
                File output = new File(cacheDir, "buildcheck.txt");
                FileWriter writer = new FileWriter(output);

                writer.write(str);
                writer.flush();
                writer.close();

                try {
                    BufferedReader br = new BufferedReader(new FileReader(output));
                    br.readLine();
                    br.readLine();
                    String line3 = br.readLine();

                    String target = line3.copyValueOf("build.number=".toCharArray());
                    String gbuild = line3.replace(target, "");
                    int buildn = Integer.parseInt(gbuild);

                    if (buildn == buildnumber) {
                        player.sendMessage(ChatColor.GRAY + "Nainstalovaná verze překladu WorldGuardu je nejnovější!");
                        player.sendMessage(ChatColor.GRAY + "Aktuální verze: WorldGuard_"
                                + WorldGuard.getVersion() + "-překlad_v"
                                    + WorldGuard.getTransVersion() + "-B" + buildnumber);
                    } else if (buildn > buildnumber){
                        player.sendMessage(ChatColor.GRAY + "Nová verze překladu WorldGuard CZ je dostupná na http://jenkins.valleycube.cz/job/WorldGuard-CZ-preklad-master/");
                        player.sendMessage(ChatColor.GRAY + "Aktuální verze: WorldGuard_"
                                + WorldGuard.getVersion() + "-překlad_v"
                                + WorldGuard.getTransVersion() + "-B" + buildnumber);
                        player.sendMessage(ChatColor.GRAY + "Nová verze: WorldGuard_"
                                + WorldGuard.getLatestVersion() + "-překlad_v"
                                    + WorldGuard.getLatestTransVersion() + "-B" + buildn);
                    } else {
                        player.sendMessage(ChatColor.RED + "Nesprávná verze překladu WorldGuardu - " + buildnumber + " místo " + buildn + "! Koukni na http://jenkins.valleycube.cz/job/WorldGuard-CZ-preklad-master/");
                    }
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Chyba při načítání updateru!");
                    e.printStackTrace();
                }
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "Chyba při načítání celého updateru!");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());
        if (wcfg.useRegions) {
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            ApplicableRegionSet chatFrom = query.getApplicableRegions(localPlayer.getLocation());

            if (!chatFrom.testState(localPlayer, Flags.SEND_CHAT)) {
                String message = chatFrom.queryValue(localPlayer, Flags.DENY_MESSAGE);
                RegionProtectionListener.formatAndSendDenyMessage("chat", localPlayer, message);
                event.setCancelled(true);
                return;
            }

            boolean anyRemoved = false;
            for (Iterator<Player> i = event.getRecipients().iterator(); i.hasNext();) {
                Player rPlayer = i.next();
                LocalPlayer rLocal = getPlugin().wrapPlayer(rPlayer);
                if (!query.testState(rLocal.getLocation(), rLocal, Flags.RECEIVE_CHAT)) {
                    i.remove();
                    anyRemoved = true;
                }
            }
            if (anyRemoved && event.getRecipients().isEmpty() && wcfg.regionCancelEmptyChatEvents) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        ConfigurationManager cfg = getConfig();

        String hostKey = cfg.hostKeys.get(player.getUniqueId().toString());
        if (hostKey == null) {
            hostKey = cfg.hostKeys.get(player.getName().toLowerCase());
        }

        if (hostKey != null) {
            String hostname = event.getHostname();
            int colonIndex = hostname.indexOf(':');
            if (colonIndex != -1) {
                hostname = hostname.substring(0, colonIndex);
            }

            if (!hostname.equals(hostKey)
                    && !(cfg.hostKeysAllowFMLClients &&
                            (hostname.equals(hostKey + "\u0000FML\u0000") || hostname.equals(hostKey + "\u0000FML2\u0000")))) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                        "Nepřipojil ses pomocí platného klíče hostitele!");
                log.warning("Kontrola hostitelského klíče WorldGuard: " +
                        player.getName() + " se připojil '" + hostname +
                        "' ale byl očekáván klíč '" + hostKey + "'. Vyhozen!");
                return;
            }
        }

        if (cfg.deopOnJoin) {
            player.setOp(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            handleBlockRightClick(event);
        } else if (event.getAction() == Action.PHYSICAL) {
            handlePhysicalInteract(event);
        }

        ConfigurationManager cfg = getConfig();
        WorldConfiguration wcfg = getWorldConfig(world);

        if (wcfg.removeInfiniteStacks
                && !getPlugin().hasPermission(player, "worldguard.override.infinite-stack")) {
            int slot = player.getInventory().getHeldItemSlot();
            ItemStack heldItem = player.getInventory().getItem(slot);
            if (heldItem != null && heldItem.getAmount() < 0) {
                player.getInventory().setItem(slot, null);
                player.sendMessage(ChatColor.RED + "Nekonečný zásobník odstraněn.");
            }
        }
    }

    /**
     * Called when a player right clicks a block.
     *
     * @param event Thrown event
     */
    private void handleBlockRightClick(PlayerInteractEvent event) {
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        Block block = event.getClickedBlock();
        World world = block.getWorld();
        Material type = block.getType();
        Player player = event.getPlayer();
        @Nullable ItemStack item = event.getItem();

        WorldConfiguration wcfg = getWorldConfig(world);

        // Infinite stack removal
        if (Materials.isInventoryBlock(type)
                && wcfg.removeInfiniteStacks
                && !getPlugin().hasPermission(player, "worldguard.override.infinite-stack")) {
            for (int slot = 0; slot < 40; slot++) {
                ItemStack heldItem = player.getInventory().getItem(slot);
                if (heldItem != null && heldItem.getAmount() < 0) {
                    player.getInventory().setItem(slot, null);
                    player.sendMessage(ChatColor.RED + "Nekonečný zásobník ve slotu #" + slot + " byl odstraněn.");
                }
            }
        }

        if (wcfg.useRegions) {
            //Block placedIn = block.getRelative(event.getBlockFace());
            ApplicableRegionSet set =
                    WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(BukkitAdapter.adapt(block.getLocation()));
            //ApplicableRegionSet placedInSet = plugin.getRegionContainer().createQuery().getApplicableRegions(placedIn.getLocation());
            LocalPlayer localPlayer = getPlugin().wrapPlayer(player);

            if (item != null && item.getType().getKey().toString().equals(wcfg.regionWand) && getPlugin().hasPermission(player, "worldguard.region.wand")) {
                if (set.size() > 0) {
                    player.sendMessage(ChatColor.YELLOW + "Můžeš stavět? " + (set.testState(localPlayer, Flags.BUILD) ? "Ano" : "Ne"));

                    StringBuilder str = new StringBuilder();
                    for (Iterator<ProtectedRegion> it = set.iterator(); it.hasNext();) {
                        str.append(it.next().getId());
                        if (it.hasNext()) {
                            str.append(", ");
                        }
                    }

                    localPlayer.print("Použitelné regiony: " + str);
                } else {
                    localPlayer.print("WorldGuard: Nejsou zde žádné definované regiony!");
                }

                event.setUseItemInHand(Event.Result.DENY);
            }
        }
    }

    /**
     * Called when a player steps on a pressure plate or tramples crops.
     *
     * @param event Thrown event
     */
    private void handlePhysicalInteract(PlayerInteractEvent event) {
        if (event.useInteractedBlock() == Event.Result.DENY) return;

        Player player = event.getPlayer();
        Block block = event.getClickedBlock(); //not actually clicked but whatever
        Material type = block.getType();
        World world = player.getWorld();

        WorldConfiguration wcfg = getWorldConfig(world);

        if (type == Material.FARMLAND && wcfg.disablePlayerCropTrampling) {
            event.setCancelled(true);
            return;
        }
        if (type == Material.TURTLE_EGG && wcfg.disablePlayerTurtleEggTrampling) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());

        if (wcfg.useRegions) {
            LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
            ApplicableRegionSet set =
                    WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(localPlayer.getLocation());

            com.sk89q.worldedit.util.Location spawn = set.queryValue(localPlayer, Flags.SPAWN_LOC);

            if (spawn != null) {
                event.setRespawnLocation(BukkitAdapter.adapt(spawn));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());

        if (wcfg.removeInfiniteStacks
                && !getPlugin().hasPermission(player, "worldguard.override.infinite-stack")) {
            int newSlot = event.getNewSlot();
            ItemStack heldItem = player.getInventory().getItem(newSlot);
            if (heldItem != null && heldItem.getAmount() < 0) {
                player.getInventory().setItem(newSlot, null);
                player.sendMessage(ChatColor.RED + "Nekonečný zásobník odstraněn.");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        ConfigurationManager cfg = getConfig();
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());

        if (wcfg.useRegions && cfg.usePlayerTeleports) {
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(event.getTo()));
            ApplicableRegionSet setFrom = query.getApplicableRegions(BukkitAdapter.adapt(event.getFrom()));

            if (event.getCause() == TeleportCause.ENDER_PEARL) {
                if (!WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, localPlayer.getWorld())) {
                    boolean cancel = false;
                    String message = null;
                    if (!setFrom.testState(localPlayer, Flags.ENDERPEARL)) {
                        cancel = true;
                        message = setFrom.queryValue(localPlayer, Flags.EXIT_DENY_MESSAGE);
                    } else if (!set.testState(localPlayer, Flags.ENDERPEARL)) {
                        cancel = true;
                        message = set.queryValue(localPlayer, Flags.ENTRY_DENY_MESSAGE);
                    }
                    if (cancel) {
                        if (message != null && !message.isEmpty()) {
                            player.sendMessage(message);
                        }
                        event.setCancelled(true);
                        return;
                    }
                }
            } else if (event.getCause() == TeleportCause.CHORUS_FRUIT) {
                if (!WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, localPlayer.getWorld())) {
                    boolean cancel = false;
                    String message = null;
                    if (!setFrom.testState(localPlayer, Flags.CHORUS_TELEPORT)) {
                        cancel = true;
                        message = setFrom.queryValue(localPlayer, Flags.EXIT_DENY_MESSAGE);
                    } else if (!set.testState(localPlayer, Flags.CHORUS_TELEPORT)) {
                        cancel = true;
                        message = set.queryValue(localPlayer, Flags.ENTRY_DENY_MESSAGE);
                    }
                    if (cancel) {
                        if (message != null && !message.isEmpty()) {
                            player.sendMessage(message);
                        }
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            if (null != WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer)
                    .testMoveTo(localPlayer, BukkitAdapter.adapt(event.getTo()), MoveType.TELEPORT)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        ConfigurationManager cfg = getConfig();
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());

        if (wcfg.useRegions && !WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, localPlayer.getWorld())) {
            ApplicableRegionSet set =
                    WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(localPlayer.getLocation());

            Set<String> allowedCommands = set.queryValue(localPlayer, Flags.ALLOWED_CMDS);
            Set<String> blockedCommands = set.queryValue(localPlayer, Flags.BLOCKED_CMDS);
            CommandFilter test = new CommandFilter(allowedCommands, blockedCommands);

            if (!test.apply(event.getMessage())) {
                String message = set.queryValue(localPlayer, Flags.DENY_MESSAGE);
                RegionProtectionListener.formatAndSendDenyMessage("použij " + event.getMessage(), localPlayer, message);
                event.setCancelled(true);
                return;
            }
        }

        if (cfg.blockInGameOp) {
            if (opPattern.matcher(event.getMessage()).matches()) {
                player.sendMessage(ChatColor.RED + "/op a /deop lze použít pouze v konzoli (jak je nastaveno ve WG nastavení).");
                event.setCancelled(true);
                return;
            }
        }
    }
}
