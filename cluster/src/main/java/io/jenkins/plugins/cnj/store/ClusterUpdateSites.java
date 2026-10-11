package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.UpdateSite;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.PluginServletFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.persistence.BlobStore;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.util.Timer;
import net.sf.json.JSONObject;

/**
 * Update-site and tool-installer metadata shared by the cluster: what any controller downloaded into its
 * {@code updates/} directory (at startup, daily, or by "Check now") is what every controller shows.
 *
 * <p>Without this, each controller had its own copy, so "Check now" refreshed only the controller that served the
 * click, and the next request (to another controller) showed the old data. Now a controller whose file is newer than
 * the cluster's publishes it ({@link #KEY} lists each file's timestamp, checksum and archive in blob storage); the
 * others write it into their own {@code updates/} with the same modification time and reload it. Because the
 * modification time is the fetch time, a controller that took the cluster's data is not due to fetch again itself, so
 * the cluster fetches about once a day rather than once per controller.
 *
 * <p>Requests to the plugin manager and the update center first take any newer data from the store, so a page loaded
 * right after "Check now" on another controller is never staler than that check.
 */
public final class ClusterUpdateSites implements Filter {

    private static final Logger LOGGER = Logger.getLogger(ClusterUpdateSites.class.getName());

    static final String KEY = "cluster/updates.json";
    static final String BLOBS = "cluster-updates/";
    static final int PERIOD_SECONDS = 60;
    static final int WATCH_SECONDS = 5;
    /** Modification times of the local files after the last {@link #sync}. */
    private static volatile Map<String, Long> seen = Map.of();
    private static long lastWarning;
    static final Pattern CHECK = Pattern.compile("^(?:/manage)?/pluginManager/checkUpdatesServer/?$");
    static final Pattern PAGES = Pattern.compile("^(?:/manage)?/(?:pluginManager|updateCenter|setupWizard)(?:/.*)?$");

    /** One shared file: when it was fetched (its modification time), its checksum and its archive. */
    record Shared(long timestamp, String sha256, String blob, @CheckForNull String previousBlob, String by) {
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("timestamp", timestamp);
            o.put("sha256", sha256);
            o.put("blob", blob);
            if (previousBlob != null) {
                o.put("previousBlob", previousBlob);
            }
            o.put("by", by);
            return o;
        }

        static Shared fromJson(JSONObject o) {
            return new Shared(o.getLong("timestamp"), o.getString("sha256"), o.getString("blob"),
                    o.optString("previousBlob", null), o.optString("by", ""));
        }
    }

    static Map<String, Shared> parse(@CheckForNull byte[] content) {
        Map<String, Shared> files = new TreeMap<>();
        if (content != null) {
            JSONObject o = JSONObject.fromObject(new String(content, StandardCharsets.UTF_8)).optJSONObject("files");
            if (o != null) {
                for (Object name : o.keySet()) {
                    files.put((String) name, Shared.fromJson(o.getJSONObject((String) name)));
                }
            }
        }
        return files;
    }

    static byte[] toBytes(Map<String, Shared> files) {
        JSONObject o = new JSONObject();
        JSONObject f = new JSONObject();
        files.forEach((name, s) -> f.put(name, s.toJson()));
        o.put("files", f);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** What to do with one file, given the local copy's modification time (0 if absent) and the cluster's entry. */
    enum Direction { NONE, PUBLISH, TAKE }

    static Direction direction(long localModified, @CheckForNull Shared shared) {
        if (shared == null) {
            return localModified > 0 ? Direction.PUBLISH : Direction.NONE;
        }
        if (localModified > shared.timestamp()) {
            return Direction.PUBLISH;
        }
        return localModified < shared.timestamp() ? Direction.TAKE : Direction.NONE;
    }

    /** Files of {@code updates/} worth sharing: data files, not temporary or backup files of their writers. */
    static boolean shareable(String name) {
        return !name.startsWith(".") && !name.endsWith(".tmp") && !name.endsWith(".bak") && !name.contains("atomic");
    }

    // ------------------------------------------------------------------ lifecycle

    @CheckForNull
    private static PostgresPersistenceStore store() {
        return PersistenceStores.get() instanceof PostgresPersistenceStore s ? s : null;
    }

    static boolean active() {
        return store() != null && PersistenceStores.getBlobStore() != null;
    }

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        PluginServletFilter.addFilter(new ClusterUpdateSites());
    }

    /**
     * A fresh home takes the cluster's data before {@code DailyCheck} first runs (periodic work starts after
     * {@code JOB_CONFIG_ADAPTED}), so that it is not due to fetch it again itself.
     */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.JOB_LOADED)
    public static void takeAtStartup() {
        sync(false);
    }

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void start() {
        PostgresPersistenceStore store = store();
        if (store == null || !active()) {
            return;
        }
        store.subscribe(e -> {
            if (e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN && KEY.equals(e.key())
                    && !store.getOrigin().equals(e.origin())) {
                Timer.get().submit(() -> sync(false));
            }
        });
        // shares what this controller's DailyCheck downloaded within seconds, so a controller starting meanwhile takes it
        // instead of fetching too; looking at modification times costs nothing, the store is read only when one changed
        Timer.get().scheduleWithFixedDelay(() -> {
            if (!localTimes().equals(seen)) {
                sync(true);
            }
        }, WATCH_SECONDS, WATCH_SECONDS, TimeUnit.SECONDS);
        Timer.get().scheduleWithFixedDelay(() -> sync(true), PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private static Map<String, Long> localTimes() {
        Map<String, Long> times = new TreeMap<>();
        File[] locals = dir().listFiles(File::isFile);
        if (locals != null) {
            for (File f : locals) {
                if (shareable(f.getName())) {
                    times.put(f.getName(), f.lastModified());
                }
            }
        }
        return times;
    }

    private static File dir() {
        return new File(Jenkins.get().getRootDir(), "updates");
    }

    /**
     * Takes newer files from the cluster and, if {@code publish}, shares local files newer than the cluster's.
     */
    static synchronized void sync(boolean publish) {
        PostgresPersistenceStore store = store();
        if (store == null || !active()) {
            return;
        }
        try {
            PersistenceStore.Entry current = store.read(KEY);
            Map<String, Shared> files = parse(current == null ? null : current.content());
            for (Map.Entry<String, Shared> f : files.entrySet()) {
                File local = new File(dir(), f.getKey());
                if (direction(local.lastModified(), f.getValue()) == Direction.TAKE) {
                    take(f.getKey(), f.getValue(), local);
                }
            }
            if (publish) {
                File[] locals = dir().listFiles(File::isFile);
                if (locals != null) {
                    for (File local : locals) {
                        if (shareable(local.getName()) && direction(local.lastModified(), files.get(local.getName())) == Direction.PUBLISH) {
                            publish(store, local);
                        }
                    }
                }
                seen = localTimes();
            }
        } catch (IOException | RuntimeException e) {
            // also called on every plugin manager request: say so once in a while, not on each (e.g. while the store is down)
            long now = System.currentTimeMillis();
            boolean warn = now - lastWarning > TimeUnit.MINUTES.toMillis(5);
            if (warn) {
                lastWarning = now;
            }
            LOGGER.log(warn ? Level.WARNING : Level.FINE, "Could not share update-site metadata with the cluster", e);
        }
    }

    private static void take(String name, Shared shared, File local) throws IOException {
        Files.createDirectories(local.getParentFile().toPath());
        if (!local.exists() || !shared.sha256().equals(ClusterPlugins.sha256(local))) {
            File tmp = new File(local.getParentFile(), "." + name + ".cluster.tmp");
            try (InputStream in = PersistenceStores.getBlobStore().get(shared.blob())) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!shared.sha256().equals(ClusterPlugins.sha256(tmp))) {
                Files.deleteIfExists(tmp.toPath());
                throw new IOException("Checksum mismatch for the cluster's " + name);
            }
            Files.move(tmp.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info(() -> "Took " + name + " from the cluster (downloaded by " + shared.by() + ")");
        }
        // the fetch time: neither due again here, nor newer than the cluster's
        Files.setLastModifiedTime(local.toPath(), FileTime.fromMillis(shared.timestamp()));
        reload(name);
    }

    /** Update sites cache their data in memory; tool installers read their file on every use. */
    private static void reload(String name) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (UpdateSite site : Jenkins.get().getUpdateCenter().getSites()) {
                if ((site.getId() + ".json").equals(name)) {
                    site.doInvalidateData();
                    site.isDue(); // reads the data's timestamp back from the file (not due: it was just fetched)
                }
            }
        }
    }

    private static void publish(PostgresPersistenceStore store, File local) throws IOException {
        String name = local.getName();
        long timestamp = local.lastModified();
        String sha256 = ClusterPlugins.sha256(local);
        BlobStore blobs = PersistenceStores.getBlobStore();
        String blob = BLOBS + name + "/" + sha256;
        boolean uploaded = false;
        for (int attempt = 0; ; attempt++) {
            PersistenceStore.Entry current = store.read(KEY);
            Map<String, Shared> files = parse(current == null ? null : current.content());
            Shared previous = files.get(name);
            if (direction(timestamp, previous) != Direction.PUBLISH) {
                return; // another controller shared newer data meanwhile
            }
            boolean sameContent = previous != null && previous.sha256().equals(sha256);
            if (!sameContent && !uploaded) {
                try (InputStream in = Files.newInputStream(local.toPath())) {
                    blobs.put(blob, in, Files.size(local.toPath()));
                }
                uploaded = true;
            }
            Shared next = sameContent
                    ? new Shared(timestamp, sha256, previous.blob(), previous.previousBlob(), PostgresPersistenceStore.NAME)
                    : new Shared(timestamp, sha256, blob, previous == null ? null : previous.blob(), PostgresPersistenceStore.NAME);
            files.put(name, next);
            try {
                store.write(KEY, toBytes(files), current == null ? PersistenceStore.ABSENT : current.version());
            } catch (ConflictException e) {
                if (attempt >= 20) {
                    throw e;
                }
                continue;
            }
            LOGGER.info(() -> "Shared " + name + " with the cluster" + (sameContent ? " (unchanged, newer check)" : ""));
            reload(name); // report the file's time, as the others do, rather than when the download started
            // keep the archive being replaced, which others may still be downloading; drop the one before it
            if (!sameContent && previous != null && previous.previousBlob() != null
                    && !previous.previousBlob().equals(blob) && !previous.previousBlob().equals(previous.blob())) {
                try {
                    blobs.delete(previous.previousBlob());
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Could not delete " + previous.previousBlob(), e);
                }
            }
            return;
        }
    }

    // ------------------------------------------------------------------ requests

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !active()) {
            chain.doFilter(request, response);
            return;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (PAGES.matcher(path).matches()) {
            sync(false); // never staler than a check made on another controller
        }
        chain.doFilter(request, response);
        if ("POST".equals(req.getMethod()) && CHECK.matcher(path).matches()) {
            sync(true); // "Check now" is synchronous: share its result before the next request
        }
    }
}
