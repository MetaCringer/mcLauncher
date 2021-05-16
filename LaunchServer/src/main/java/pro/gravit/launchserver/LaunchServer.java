package pro.gravit.launchserver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.Launcher;
import pro.gravit.launcher.NeedGarbageCollection;
import pro.gravit.launcher.hasher.HashedDir;
import pro.gravit.launcher.managers.ConfigManager;
import pro.gravit.launcher.managers.GarbageManager;
import pro.gravit.launcher.modules.events.ClosePhase;
import pro.gravit.launcher.profiles.ClientProfile;
import pro.gravit.launchserver.auth.AuthProviderPair;
import pro.gravit.launchserver.auth.session.MemorySessionStorage;
import pro.gravit.launchserver.binary.*;
import pro.gravit.launchserver.config.LaunchServerConfig;
import pro.gravit.launchserver.config.LaunchServerRuntimeConfig;
import pro.gravit.launchserver.launchermodules.LauncherModuleLoader;
import pro.gravit.launchserver.manangers.*;
import pro.gravit.launchserver.manangers.hook.AuthHookManager;
import pro.gravit.launchserver.modules.events.*;
import pro.gravit.launchserver.modules.impl.LaunchServerModulesManager;
import pro.gravit.launchserver.socket.handlers.NettyServerSocketHandler;
import pro.gravit.utils.command.Command;
import pro.gravit.utils.command.CommandHandler;
import pro.gravit.utils.command.SubCommand;
import pro.gravit.utils.helper.CommonHelper;
import pro.gravit.utils.helper.IOHelper;
import pro.gravit.utils.helper.JVMHelper;
import pro.gravit.utils.helper.SecurityHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The main LaunchServer class. Contains links to all necessary objects
 * Not a singletron
 */
public final class LaunchServer implements Runnable, AutoCloseable, Reconfigurable {
    private final Logger logger = LogManager.getLogger();

    public static final Class<? extends LauncherBinary> defaultLauncherEXEBinaryClass = null;
    /**
     * Working folder path
     */
    public final Path dir;
    /**
     * Environment type (test / production)
     */
    public final LaunchServerEnv env;
    /**
     * The path to the folder with libraries for the launcher
     */
    public final Path launcherLibraries;
    /**
     * The path to the folder with compile-only libraries for the launcher
     */
    public final Path launcherLibrariesCompile;

    // Constant paths
    /**
     * The path to the folder with updates/webroot
     */
    public final Path updatesDir;
    /**
     * Save/Reload LaunchServer config
     */
    public final LaunchServerConfigManager launchServerConfigManager;
    /**
     * The path to the folder with profiles
     */
    public final Path profilesDir;
    public final Path tmpDir;
    /**
     * This object contains runtime configuration
     */
    public final LaunchServerRuntimeConfig runtime;
    @Deprecated
    public final ECPublicKey publicKey;
    @Deprecated
    public final ECPrivateKey privateKey;
    /**
     * Pipeline for building JAR
     */
    public final JARLauncherBinary launcherBinary;
    /**
     * Pipeline for building EXE
     */
    public final LauncherBinary launcherEXEBinary;

    //public static LaunchServer server = null;
    public final Class<? extends LauncherBinary> launcherEXEBinaryClass;
    // Server config
    public final SessionManager sessionManager;
    public final AuthHookManager authHookManager;
    public final LaunchServerModulesManager modulesManager;
    // Launcher binary
    public final MirrorManager mirrorManager;
    public final AuthManager authManager;
    public final ReconfigurableManager reconfigurableManager;
    public final ConfigManager configManager;
    public final PingServerManager pingServerManager;
    public final FeaturesManager featuresManager;
    public final KeyAgreementManager keyAgreementManager;
    // HWID ban + anti-brutforce
    public final CertificateManager certificateManager;
    // Server
    public final CommandHandler commandHandler;
    public final NettyServerSocketHandler nettyServerSocketHandler;
    @Deprecated
    public final Timer taskPool;
    public final ScheduledExecutorService service;
    public final AtomicBoolean started = new AtomicBoolean(false);
    public final LauncherModuleLoader launcherModuleLoader;
    public LaunchServerConfig config;
    public volatile Map<String, HashedDir> updatesDirMap;
    // Updates and profiles
    private volatile Set<ClientProfile> profilesList;

    public LaunchServer(LaunchServerDirectories directories, LaunchServerEnv env, LaunchServerConfig config, LaunchServerRuntimeConfig runtimeConfig, LaunchServerConfigManager launchServerConfigManager, LaunchServerModulesManager modulesManager, KeyAgreementManager keyAgreementManager, CommandHandler commandHandler, CertificateManager certificateManager) throws IOException {
        this.dir = directories.dir;
        this.tmpDir = directories.tmpDir;
        this.env = env;
        this.config = config;
        this.launchServerConfigManager = launchServerConfigManager;
        this.modulesManager = modulesManager;
        this.profilesDir = directories.profilesDir;
        this.updatesDir = directories.updatesDir;
        this.keyAgreementManager = keyAgreementManager;
        this.publicKey = keyAgreementManager.ecdsaPublicKey;
        this.privateKey = keyAgreementManager.ecdsaPrivateKey;
        this.commandHandler = commandHandler;
        this.runtime = runtimeConfig;
        this.certificateManager = certificateManager;
        this.service = Executors.newScheduledThreadPool(config.netty.performance.schedulerThread);
        taskPool = new Timer("Timered task worker thread", true);
        launcherLibraries = directories.launcherLibrariesDir;
        launcherLibrariesCompile = directories.launcherLibrariesCompileDir;

        config.setLaunchServer(this);

        modulesManager.invokeEvent(new NewLaunchServerInstanceEvent(this));

        // Print keypair fingerprints

        // Load class bindings.
        launcherEXEBinaryClass = defaultLauncherEXEBinaryClass;

        runtime.verify();
        config.verify();
        if (config.sessions == null) config.sessions = new MemorySessionStorage();

        // build hooks, anti-brutforce and other
        sessionManager = new SessionManager(this);
        mirrorManager = new MirrorManager();
        reconfigurableManager = new ReconfigurableManager();
        authHookManager = new AuthHookManager();
        configManager = new ConfigManager();
        pingServerManager = new PingServerManager(this);
        featuresManager = new FeaturesManager(this);
        authManager = new AuthManager(this);
        //Generate or set new Certificate API
        certificateManager.orgName = config.projectName;
        config.init(ReloadType.FULL);
        registerObject("launchServer", this);
        GarbageManager.registerNeedGC(sessionManager);

        pro.gravit.launchserver.command.handler.CommandHandler.registerCommands(commandHandler, this);

        // init modules
        modulesManager.invokeEvent(new LaunchServerInitPhase(this));

        // Set launcher EXE binary
        launcherBinary = new JARLauncherBinary(this);
        launcherEXEBinary = binary();

        launcherBinary.init();
        launcherEXEBinary.init();
        syncLauncherBinaries();
        launcherModuleLoader = new LauncherModuleLoader(this);
        if (config.components != null) {
            logger.debug("Init components");
            config.components.forEach((k, v) -> {
                logger.debug("Init component {}", k);
                v.setComponentName(k);
                v.init(this);
            });
            logger.debug("Init components successful");
        }
        launcherModuleLoader.init();
        nettyServerSocketHandler = new NettyServerSocketHandler(this);
        // post init modules
        modulesManager.invokeEvent(new LaunchServerPostInitPhase(this));
    }

    public void reload(ReloadType type) throws Exception {
        config.close(type);
        Map<String, AuthProviderPair> pairs = null;
        if (type.equals(ReloadType.NO_AUTH)) {
            pairs = config.auth;
        }
        logger.info("Reading LaunchServer config file");
        config = launchServerConfigManager.readConfig();
        config.setLaunchServer(this);
        if (type.equals(ReloadType.NO_AUTH)) {
            config.auth = pairs;
        }
        config.verify();
        config.init(type);
        if (type.equals(ReloadType.FULL) && config.components != null) {
            logger.debug("Init components");
            config.components.forEach((k, v) -> {
                logger.debug("Init component {}", k);
                v.setComponentName(k);
                v.init(this);
            });
            logger.debug("Init components successful");
        }

    }

    @Override
    public Map<String, Command> getCommands() {
        Map<String, Command> commands = new HashMap<>();
        SubCommand reload = new SubCommand() {
            @Override
            public void invoke(String... args) throws Exception {
                if (args.length == 0) {
                    reload(ReloadType.FULL);
                    return;
                }
                switch (args[0]) {
                    case "full":
                        reload(ReloadType.FULL);
                        break;
                    case "no_auth":
                        reload(ReloadType.NO_AUTH);
                        break;
                    case "no_components":
                        reload(ReloadType.NO_COMPONENTS);
                        break;
                    default:
                        reload(ReloadType.FULL);
                        break;
                }
            }
        };
        commands.put("reload", reload);
        SubCommand save = new SubCommand() {
            @Override
            public void invoke(String... args) throws Exception {
                launchServerConfigManager.writeConfig(config);
                launchServerConfigManager.writeRuntimeConfig(runtime);
                logger.info("LaunchServerConfig saved");
            }
        };
        commands.put("save", save);
        return commands;
    }

    private LauncherBinary binary() {
        if (launcherEXEBinaryClass != null) {
            try {
                return (LauncherBinary) MethodHandles.publicLookup().findConstructor(launcherEXEBinaryClass, MethodType.methodType(void.class, LaunchServer.class)).invoke(this);
            } catch (Throwable e) {
                logger.error(e);
            }
        }
        try {
            Class.forName("net.sf.launch4j.Builder");
            if (config.launch4j.enabled) return new EXEL4JLauncherBinary(this);
        } catch (ClassNotFoundException ignored) {
            logger.warn("Launch4J isn't in classpath.");
        }
        return new EXELauncherBinary(this);
    }

    public void buildLauncherBinaries() throws IOException {
        launcherBinary.build();
        launcherEXEBinary.build();
    }

    public void close() throws Exception {
        service.shutdownNow();
        logger.info("Close server socket");
        nettyServerSocketHandler.close();
        // Close handlers & providers
        config.close(ReloadType.FULL);
        modulesManager.invokeEvent(new ClosePhase());
        logger.info("Save LaunchServer runtime config");
        launchServerConfigManager.writeRuntimeConfig(runtime);
        // Print last message before death :(
        logger.info("LaunchServer stopped");
    }

    public Set<ClientProfile> getProfiles() {
        return profilesList;
    }

    public void setProfiles(Set<ClientProfile> profilesList) {
        this.profilesList = Collections.unmodifiableSet(profilesList);
    }

    public HashedDir getUpdateDir(String name) {
        return updatesDirMap.get(name);
    }

    public Set<Entry<String, HashedDir>> getUpdateDirs() {
        return updatesDirMap.entrySet();
    }

    public void rebindNettyServerSocket() {
        nettyServerSocketHandler.close();
        CommonHelper.newThread("Netty Server Socket Thread", false, nettyServerSocketHandler).start();
    }

    @Override
    public void run() {
        if (started.getAndSet(true))
            throw new IllegalStateException("LaunchServer has been already started");

        // Add shutdown hook, then start LaunchServer
        if (!this.env.equals(LaunchServerEnv.TEST)) {
            JVMHelper.RUNTIME.addShutdownHook(CommonHelper.newThread(null, false, () -> {
                try {
                    close();
                } catch (Exception e) {
                    logger.error(e);
                }
            }));
            CommonHelper.newThread("Command Thread", true, commandHandler).start();
            // Sync updates dir
            CommonHelper.newThread("Profiles and updates sync", true, () -> {
                try {
                    if (!IOHelper.isDir(updatesDir))
                        Files.createDirectory(updatesDir);
                    syncUpdatesDir(null);
                    modulesManager.invokeEvent(new LaunchServerUpdatesSyncEvent(this));

                    // Sync profiles dir
                    if (!IOHelper.isDir(profilesDir))
                        Files.createDirectory(profilesDir);
                    syncProfilesDir();
                    modulesManager.invokeEvent(new LaunchServerProfilesSyncEvent(this));
                } catch (IOException e) {
                    logger.error(e);
                    logger.error("Updates/Profiles not synced");
                }
            }).start();
        }
        if (config.netty != null)
            rebindNettyServerSocket();
        try {
            modulesManager.fullInitializedLaunchServer(this);
            modulesManager.invokeEvent(new LaunchServerFullInitEvent(this));
            logger.info("LaunchServer started");
        } catch (Throwable e) {
            logger.error(e);
            JVMHelper.RUNTIME.exit(-1);
        }
    }

    public void syncLauncherBinaries() throws IOException {
        logger.info("Syncing launcher binaries");

        // Syncing launcher binary
        logger.info("Syncing launcher binary file");
        if (!launcherBinary.sync()) logger.warn("Missing launcher binary file");

        // Syncing launcher EXE binary
        logger.info("Syncing launcher EXE binary file");
        if (!launcherEXEBinary.sync() && config.launch4j.enabled)
            logger.warn("Missing launcher EXE binary file");

    }

    public void syncProfilesDir() throws IOException {
        logger.info("Syncing profiles dir");
        List<ClientProfile> newProfies = new LinkedList<>();
        IOHelper.walk(profilesDir, new ProfilesFileVisitor(newProfies), false);

        // Sort and set new profiles
        newProfies.sort(Comparator.comparing(a -> a));
        profilesList = Set.copyOf(newProfies);
        if (pingServerManager != null)
            pingServerManager.syncServers();
    }

    public void syncUpdatesDir(Collection<String> dirs) throws IOException {
        logger.info("Syncing updates dir");
        Map<String, HashedDir> newUpdatesDirMap = new HashMap<>(16);
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(updatesDir)) {
            for (final Path updateDir : dirStream) {
                if (Files.isHidden(updateDir))
                    continue; // Skip hidden

                // Resolve name and verify is dir
                String name = IOHelper.getFileName(updateDir);
                if (!IOHelper.isDir(updateDir)) {
                    if (!IOHelper.isFile(updateDir) && Stream.of(".jar", ".exe", ".hash").noneMatch(e -> updateDir.toString().endsWith(e)))
                        logger.warn("Not update dir: '{}'", name);
                    continue;
                }

                // Add from previous map (it's guaranteed to be non-null)
                if (dirs != null && !dirs.contains(name)) {
                    HashedDir hdir = updatesDirMap.get(name);
                    if (hdir != null) {
                        newUpdatesDirMap.put(name, hdir);
                        continue;
                    }
                }

                // Sync and sign update dir
                logger.info("Syncing '{}' update dir", name);
                HashedDir updateHDir = new HashedDir(updateDir, null, true, true);
                newUpdatesDirMap.put(name, updateHDir);
            }
        }
        updatesDirMap = Collections.unmodifiableMap(newUpdatesDirMap);
    }

    public void restart() {
        ProcessBuilder builder = new ProcessBuilder();
        if (config.startScript != null) builder.command(Collections.singletonList(config.startScript));
        else throw new IllegalArgumentException("Please create start script and link it as startScript in config.");
        builder.directory(this.dir.toFile());
        builder.inheritIO();
        builder.redirectErrorStream(true);
        builder.redirectOutput(Redirect.PIPE);
        try {
            builder.start();
        } catch (IOException e) {
            logger.error(e);
        }
    }

    public void registerObject(String name, Object object) {
        if (object instanceof Reconfigurable) {
            reconfigurableManager.registerReconfigurable(name, (Reconfigurable) object);
        }
        if (object instanceof NeedGarbageCollection) {
            GarbageManager.registerNeedGC((NeedGarbageCollection) object);
        }
    }

    public void unregisterObject(String name, Object object) {
        if (object instanceof Reconfigurable) {
            reconfigurableManager.unregisterReconfigurable(name);
        }
        if (object instanceof NeedGarbageCollection) {
            GarbageManager.unregisterNeedGC((NeedGarbageCollection) object);
        }
    }

    public void fullyRestart() {
        restart();
        JVMHelper.RUNTIME.exit(0);
    }


    public enum ReloadType {
        NO_AUTH,
        NO_COMPONENTS,
        FULL
    }

    public enum LaunchServerEnv {
        TEST,
        DEV,
        DEBUG,
        PRODUCTION
    }

    public interface LaunchServerConfigManager {
        LaunchServerConfig readConfig() throws IOException;

        LaunchServerRuntimeConfig readRuntimeConfig() throws IOException;

        void writeConfig(LaunchServerConfig config) throws IOException;

        void writeRuntimeConfig(LaunchServerRuntimeConfig config) throws IOException;
    }

    private static final class ProfilesFileVisitor extends SimpleFileVisitor<Path> {
        private final Collection<ClientProfile> result;
        private final Logger logger = LogManager.getLogger();

        private ProfilesFileVisitor(Collection<ClientProfile> result) {
            this.result = result;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            logger.info("Syncing '{}' profile", IOHelper.getFileName(file));

            // Read profile
            ClientProfile profile;
            try (BufferedReader reader = IOHelper.newReader(file)) {
                profile = Launcher.gsonManager.gson.fromJson(reader, ClientProfile.class);
            }
            profile.verify();

            // Add SIGNED profile to result list
            result.add(profile);
            return super.visitFile(file, attrs);
        }
    }

    public static class LaunchServerDirectories {
        public static final String UPDATES_NAME = "updates", PROFILES_NAME = "profiles",
                TRUSTSTORE_NAME = "truststore", LAUNCHERLIBRARIES_NAME = "launcher-libraries",
                LAUNCHERLIBRARIESCOMPILE_NAME = "launcher-libraries-compile", KEY_NAME = ".keys";
        public Path updatesDir;
        public Path profilesDir;
        public Path launcherLibrariesDir;
        public Path launcherLibrariesCompileDir;
        public Path keyDirectory;
        public Path dir;
        public Path trustStore;
        public Path tmpDir;

        public void collect() {
            if (updatesDir == null) updatesDir = getPath(UPDATES_NAME);
            if (profilesDir == null) profilesDir = getPath(PROFILES_NAME);
            if (trustStore == null) trustStore = getPath(TRUSTSTORE_NAME);
            if (launcherLibrariesDir == null) launcherLibrariesDir = getPath(LAUNCHERLIBRARIES_NAME);
            if (launcherLibrariesCompileDir == null)
                launcherLibrariesCompileDir = getPath(LAUNCHERLIBRARIESCOMPILE_NAME);
            if(keyDirectory == null) keyDirectory = getPath(KEY_NAME);
            if(tmpDir ==null) tmpDir = Paths.get(System.getProperty("java.io.tmpdir")).resolve(String.format("launchserver-%s", SecurityHelper.randomStringToken()));
        }

        private Path getPath(String dirName) {
            String property = System.getProperty("launchserver.dir."+dirName, null);
            if(property == null) return dir.resolve(dirName);
            else return Paths.get(property);
        }
    }
}
