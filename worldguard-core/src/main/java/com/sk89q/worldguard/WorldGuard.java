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

package com.sk89q.worldguard;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.task.SimpleSupervisor;
import com.sk89q.worldedit.util.task.Supervisor;
import com.sk89q.worldedit.util.task.Task;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.flags.registry.SimpleFlagRegistry;
import com.sk89q.worldguard.util.WorldGuardExceptionConverter;
import com.sk89q.worldguard.util.concurrent.EvenMoreExecutors;
import com.sk89q.worldguard.util.profile.cache.HashMapCache;
import com.sk89q.worldguard.util.profile.cache.ProfileCache;
import com.sk89q.worldguard.util.profile.cache.SQLiteCache;
import com.sk89q.worldguard.util.profile.resolver.ProfileService;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;

public final class WorldGuard {

    public static final Logger logger = Logger.getLogger(WorldGuard.class.getCanonicalName());

    private static String version;
    private static String transVersion;
    private static String latestTransVersion;
    private static String latestVersion;
    private static final WorldGuard instance = new WorldGuard();

    private static WorldGuardPlatform platform;
    private final SimpleFlagRegistry flagRegistry = new SimpleFlagRegistry();
    private final Supervisor supervisor = new SimpleSupervisor();
    private ProfileCache profileCache;
    private ProfileService profileService;
    private ListeningExecutorService executorService;
    private WorldGuardExceptionConverter exceptionConverter = new WorldGuardExceptionConverter();

    static {
        Flags.registerAll();
    }

    public static WorldGuard getInstance() {
        return instance;
    }
    public static HashMap<String, String> messageData = new HashMap<String, String>();

    private WorldGuard() {
    }

    public void setup() {
        executorService = MoreExecutors.listeningDecorator(EvenMoreExecutors.newBoundedCachedThreadPool(0, 1, 20,
                "WorldGuard vykonávač úloh - %s"));

        File cacheDir = new File(getPlatform().getConfigDir().toFile(), "cache");
        cacheDir.mkdirs();

        File messagesDir = new File(getPlatform().getConfigDir().toFile(), "messages");
        if (!messagesDir.exists()) {
            try {
                messagesDir.mkdirs();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Yaml yaml = new Yaml();
        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("messages/messages.yml");
        File file = new File(String.valueOf(this.getClass().getResource("messages/messages.yml")));
        if (!file.exists()){
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, Object>yamlMap = yaml.load(inputStream);
        yamlMap.put("version", getTransVersion());
        setMessage("deny-message-prefix", "&c&lStop!");

        for(String mess : yamlMap.keySet()) {
            messageData.put(mess, (String) yamlMap.get(mess));
        }

        try {
            profileCache = new SQLiteCache(new File(cacheDir, "profiles.sqlite"));
        } catch (IOException | UnsatisfiedLinkError ignored) {
            logger.log(Level.WARNING, "Nepodařilo se inicializovat mezipaměť profilu SQLite. Cache je pouze v paměti.");
            profileCache = new HashMapCache();
        }

        profileService = getPlatform().createProfileService(profileCache);

        getPlatform().load();
    }

    /**
     * The WorldGuard Platform.
     * The Platform is only available after WorldGuard is enabled.
     *
     * @return The platform
     */
    public WorldGuardPlatform getPlatform() {
        checkNotNull(platform, "WorldGuard není načten! Není možné načíst WorldEdit, nebo jinou platformu.");
        return platform;
    }

    public void setPlatform(WorldGuardPlatform platform) {
        checkNotNull(platform);
        this.platform = platform;
    }

    /**
     * Get the flag registry.
     *
     * @return the flag registry
     */
    public FlagRegistry getFlagRegistry() {
        return this.flagRegistry;
    }

    /**
     * Get the supervisor.
     *
     * @return the supervisor
     */
    public Supervisor getSupervisor() {
        return supervisor;
    }

    /**
     * Get the global executor service for internal usage (please use your
     * own executor service).
     *
     * @return the global executor service
     */
    public ListeningExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Get the profile lookup service.
     *
     * @return the profile lookup service
     */
    public ProfileService getProfileService() {
        return profileService;
    }

    /**
     * Get the profile cache.
     *
     * @return the profile cache
     */
    public ProfileCache getProfileCache() {
        return profileCache;
    }

    /**
     * Get the exception converter
     *
     * @return the exception converter
     */
    public WorldGuardExceptionConverter getExceptionConverter() {
        return exceptionConverter;
    }

    /**
     * Checks to see if the sender is a player, otherwise throw an exception.
     *
     * @param sender The sender
     * @return The player
     * @throws CommandException if it isn't a player
     */
    public LocalPlayer checkPlayer(Actor sender) throws CommandException {
        if (sender instanceof LocalPlayer) {
            return (LocalPlayer) sender;
        } else {
            throw new CommandException("A player is expected.");
        }
    }

    /**
     * Called when WorldGuard should be disabled.
     */
    public void disable() {
        executorService.shutdown();

        try {
            logger.log(Level.INFO, "Shutting down executor and cancelling any pending tasks...");

            List<Task<?>> tasks = supervisor.getTasks();
            if (!tasks.isEmpty()) {
                StringBuilder builder = new StringBuilder("Known tasks:");
                for (Task<?> task : tasks) {
                    builder.append("\n");
                    builder.append(task.getName());
                    task.cancel(true);
                }
                logger.log(Level.INFO, builder.toString());
            }

            //Futures.successfulAsList(tasks).get();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        platform.unload();
    }

    /**
     * Get the version.
     *
     * @return the version of WorldEdit
     */
    public static String getVersion() {
        if (version != null) {
            return version;
        }

        Package p = WorldGuard.class.getPackage();

        if (p == null) {
            p = Package.getPackage("com.sk89q.worldguard");
        }

        if (p == null) {
            version = "(unknown)";
        } else {
            version = p.getImplementationVersion();

            if (version == null) {
                version = "(unknown)";
            }
        }

        return version;
    }
    /**
     * Verze překladu :)
     */
    public static String getTransVersion() {
        transVersion = "0.1-beta";
        return transVersion;
    }
    public static String getLatestTransVersion() {
        String versionn = null;
        try {
            String versionurl = "http://jenkins.valleycube.cz/job/WorldGuard-CZ-preklad/ws/trans_version.number";
            URL url = new URL(versionurl);
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
            File output = new File(cacheDir, "transversioncheck.txt");
            FileWriter writer = new FileWriter(output);

            writer.write(str);
            writer.flush();
            writer.close();

            Path versionfile = Path.of(cacheDir + "/transversioncheck.txt");
            versionn = Files.readString(versionfile);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Nepodařilo se získat poslední verzi z GitHub! Kontaktuj podporu...");
        }
        latestTransVersion = versionn;

        return latestTransVersion;
    }
    public static String getLatestVersion() {
        String latestver = null;
        try {
            String versionurl = "http://jenkins.valleycube.cz/job/WorldGuard-CZ-preklad/ws/version.number";
            URL urlvn = new URL(versionurl);
            URLConnection con = urlvn.openConnection();
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
            File output = new File(cacheDir, "versioncheck.txt");
            FileWriter writer = new FileWriter(output);

            writer.write(str);
            writer.flush();
            writer.close();

            Path versionfile = Path.of(cacheDir + "/versioncheck.txt");
            latestver = Files.readString(versionfile);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Nepodařilo se získat poslední verzi z GitHub! Kontaktuj podporu...");
        }
        latestVersion = latestver;

        return latestVersion;

    }
    private void setMessage(String name, Object message) {
        Yaml yaml = new Yaml();
        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("messages/messages.yml");
        Map<String, Object>yamlMap = yaml.load(inputStream);
        if (!yamlMap.containsValue(name)) {
            yamlMap.put(name, message);
        }
    }
}
