package apoc;

import apoc.export.util.ExportConfig;
import apoc.load.Jdbc;
import apoc.util.SimpleRateLimiter;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.combined.CombinedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.ex.ConversionException;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.logging.NullLog;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.impl.GlobalProceduresRegistry;

import java.lang.reflect.Field;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static apoc.util.FileUtils.isFile;
import static org.neo4j.configuration.GraphDatabaseSettings.SYSTEM_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.data_directory;
import static org.neo4j.configuration.GraphDatabaseSettings.load_csv_file_url_root;
import static org.neo4j.configuration.GraphDatabaseSettings.logical_logs_location;
import static org.neo4j.configuration.GraphDatabaseSettings.logs_directory;
import static org.neo4j.configuration.GraphDatabaseSettings.neo4j_home;
import static org.neo4j.configuration.GraphDatabaseSettings.plugin_dir;
import static org.neo4j.configuration.GraphDatabaseSettings.transaction_logs_root_path;

public class ApocConfig extends LifecycleAdapter {

    public static final String SUN_JAVA_COMMAND = "sun.java.command";
    public static final String APOC_IMPORT_FILE_ENABLED = "apoc.import.file.enabled";
    public static final String APOC_EXPORT_FILE_ENABLED = "apoc.export.file.enabled";
    public static final String APOC_IMPORT_FILE_USE_NEO4J_CONFIG = "apoc.import.file.use_neo4j_config";
    public static final String APOC_TTL_SCHEDULE = "apoc.ttl.schedule";
    public static final String APOC_TTL_ENABLED = "apoc.ttl.enabled";
    public static final String APOC_TTL_LIMIT = "apoc.ttl.limit";
    public static final String APOC_TRIGGER_ENABLED = "apoc.trigger.enabled";
    public static final String APOC_UUID_ENABLED = "apoc.uuid.enabled";
    public static final String APOC_JSON_ZIP_URL = "apoc.json.zip.url";  // TODO: check if really needed
    public static final String APOC_JSON_SIMPLE_JSON_URL = "apoc.json.simpleJson.url"; // TODO: check if really needed
    public static final String APOC_IMPORT_FILE_ALLOW__READ__FROM__FILESYSTEM = "apoc.import.file.allow_read_from_filesystem";
    public static final String APOC_CONFIG_JOBS_SCHEDULED_NUM_THREADS = "apoc.jobs.scheduled.num_threads";
    public static final String APOC_CONFIG_JOBS_POOL_NUM_THREADS = "apoc.jobs.pool.num_threads";
    public static final String APOC_CONFIG_INITIALIZER_CYPHER = "apoc.initializer.cypher";
    public static final String APOC_SEMAPHORE_PREFIX = "apoc.semaphore";
    public static final String APOC_SEMAPHORE_DEFAULT_NAME = APOC_SEMAPHORE_PREFIX + ".default.name";

    private static final List<Setting> NEO4J_DIRECTORY_CONFIGURATION_SETTING_NAMES = new ArrayList<>(Arrays.asList(
            data_directory,
            load_csv_file_url_root,
            logs_directory,
            plugin_dir,
            logical_logs_location,
            transaction_logs_root_path,
            neo4j_home
    ));
    private static final String DEFAULT_PATH = ".";
    private static final String CONFIG_DIR = "config-dir=";

    private final Config neo4jConfig;
    private final Log log;
    private final GlobalProceduresRegistry globalProceduresRegistry;
    private final DatabaseManagementService databaseManagementService;

    private Configuration config;

    private static ApocConfig theInstance;
    private LoggingType loggingType;
    private SimpleRateLimiter rateLimiter;
    private GraphDatabaseService systemDb;

    public ApocConfig(Config neo4jConfig, LogService log, GlobalProceduresRegistry globalProceduresRegistry, DatabaseManagementService databaseManagementService) {
        this.neo4jConfig = neo4jConfig;
        this.log = log.getInternalLog(ApocConfig.class);
        this.globalProceduresRegistry = globalProceduresRegistry;
        this.databaseManagementService = databaseManagementService;
        theInstance = this;

        // expose this config instance via `@Context ApocConfig config`
        globalProceduresRegistry.registerComponent((Class<ApocConfig>) getClass(), ctx -> this, true);
        this.log.info("successfully registered ApocConfig for @Context");
    }

    // use only for unit tests
    public ApocConfig() {
        this.neo4jConfig = null;
        this.log = NullLog.getInstance();
        this.globalProceduresRegistry = null;
        this.databaseManagementService = null;
        theInstance = this;
        this.config = new PropertiesConfiguration();
    }

    public Configuration getConfig() {
        return config;
    }

    @Override
    public void init() throws Exception {
        log.debug("called init");
        // grab NEO4J_CONF from environment. If not set, calculate it from sun.java.command system property
        String neo4jConfFolder = System.getenv().getOrDefault("NEO4J_CONF", determineNeo4jConfFolder());
        System.setProperty("NEO4J_CONF", neo4jConfFolder);
        log.info("system property NEO4J_CONF set to %s", neo4jConfFolder);
        loadConfiguration();
    }

    protected String determineNeo4jConfFolder() {
        String command = System.getProperty(SUN_JAVA_COMMAND);
        if (command == null) {
            log.warn("system property %s is not set, assuming '.' as conf dir. This might cause `apoc.conf` not getting loaded.", SUN_JAVA_COMMAND);
            return DEFAULT_PATH;
        } else {
            final String neo4jConfFolder = Stream.of(command.split("--"))
                    .map(String::trim)
                    .filter(s -> s.startsWith(CONFIG_DIR))
                    .map(s -> s.substring(CONFIG_DIR.length()))
                    .findFirst()
                    .orElse(DEFAULT_PATH);
            if (DEFAULT_PATH.equals(neo4jConfFolder)) {
                log.info("cannot determine conf folder from sys property %s, assuming '.' ", command);
            } else {
                log.info("from system properties: NEO4J_CONF=%s", neo4jConfFolder);
            }
            return neo4jConfFolder;
        }
    }

    /**
     * use apache commons to load configuration
     * classpath:/apoc-config.xml contains a description where to load configuration from
     */
    protected void loadConfiguration() {
        try {

            URL resource = getClass().getClassLoader().getResource("apoc-config.xml");
            log.info("loading apoc meta config from %s", resource.toString());
            CombinedConfigurationBuilder builder = new CombinedConfigurationBuilder()
                    .configure(new Parameters().fileBased().setURL(resource));
            config = builder.getConfiguration();

            // copy apoc settings from neo4j.conf for legacy support
            neo4jConfig.getDeclaredSettings().entrySet().stream()
                    .filter(e -> !config.containsKey(e.getKey()))
                    .filter(e -> e.getKey().startsWith("apoc."))
                    .forEach(e -> {
                        log.info("setting from neo4j.conf: " + e.getKey() + "=" + neo4jConfig.get(e.getValue()));
                        config.setProperty(e.getKey(), neo4jConfig.get(e.getValue()));
                    });

            addDbmsDirectoriesMetricsSettings();
            for (Setting s : NEO4J_DIRECTORY_CONFIGURATION_SETTING_NAMES) {
                Object value = neo4jConfig.get(s);
                if (value!=null) {
                    config.setProperty(s.name(), value.toString());
                }
            }

            boolean allowFileUrls = neo4jConfig.get(GraphDatabaseSettings.allow_file_urls);
            config.setProperty(APOC_IMPORT_FILE_ALLOW__READ__FROM__FILESYSTEM, allowFileUrls);

            loadJdbcDrivers();

            initLogging();
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    private void addDbmsDirectoriesMetricsSettings() {
        try {
            Class<?> metricsSettingsClass = Class.forName("com.neo4j.kernel.impl.enterprise.configuration.MetricsSettings");
            Field csvPathField = metricsSettingsClass.getDeclaredField("csvPath");
            Setting<Path> dbms_directories_metrics = (Setting<Path>) csvPathField.get(null);
            NEO4J_DIRECTORY_CONFIGURATION_SETTING_NAMES.add(dbms_directories_metrics);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            // ignore - on community edition that class does not exist
        }
    }

    public LoggingType getLoggingType() {
        return loggingType;
    }

    public SimpleRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public void setLoggingType(LoggingType loggingType) {
        this.loggingType = loggingType;
    }

    public void setRateLimiter(SimpleRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public GraphDatabaseService getSystemDb() {
        if (systemDb == null) {
            try {
                systemDb = databaseManagementService.database(SYSTEM_DATABASE_NAME);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return systemDb;
    }

    public enum LoggingType {none, safe, raw}

    private void initLogging() {
        loggingType = LoggingType.valueOf(getString("apoc.user.log.type", "safe").trim());
        rateLimiter = new SimpleRateLimiter(getInt( "apoc.user.log.window.time", 10000), getInt("apoc.user.log.window.ops", 10));
    }

    private void loadJdbcDrivers() {
        Iterators.stream(config.getKeys("apoc.jdbc"))
                .filter(k -> k.endsWith("driver"))
                .forEach(k -> Jdbc.loadDriver(k));
    }

    public void checkReadAllowed(String url) {
        if (isFile(url) && !config.getBoolean(APOC_IMPORT_FILE_ENABLED)) {
            throw new RuntimeException("Import from files not enabled," +
                    " please set apoc.import.file.enabled=true in your apoc.conf");
        }
    }

    public void checkWriteAllowed(ExportConfig exportConfig) {
        if (!config.getBoolean(APOC_EXPORT_FILE_ENABLED)) {
            if (exportConfig==null || !exportConfig.streamStatements()) {
                throw new RuntimeException("Export to files not enabled, please set apoc.export.file.enabled=true in your apoc.conf");
            }
        }
    }

    public static ApocConfig apocConfig() {
        return theInstance;
    }


    /*
     * delegate methods for Configuration
     */

    public Iterator<String> getKeys(String prefix) {
        return getConfig().getKeys(prefix);
    }

    public boolean containsKey(String key) {
        return getConfig().containsKey(key);
    }

    public String getString(String key) {
        return getConfig().getString(key);
    }

    public String getString(String key, String defaultValue) {
        return getConfig().getString(key, defaultValue);
    }

    public void setProperty(String key, Object value) {
        getConfig().setProperty(key, value);
    }

    public boolean getBoolean(String key) {
        return getConfig().getBoolean(key);
    }

    public boolean isImportFolderConfigured() {
        // in case we're test database import path is TestDatabaseManagementServiceBuilder.EPHEMERAL_PATH

        String importFolder = config.getString("dbms.directories.import");
        if (importFolder==null) {
            return false;
        } else {
            return !"/target/test data/neo4j".equals(importFolder);
        }
    }

    public int getInt(String key, int defaultValue) {
        try {
            return getConfig().getInt(key, defaultValue);
        } catch (ConversionException e) {
            Object o = getConfig().getProperty(key);
            if (o instanceof Duration) {
                return (int) ((Duration)o).getSeconds();
            } else {
                throw new IllegalArgumentException("don't know how to convert for config option " + key, e);
            }
        }
    }
}
