package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.PluginWrapper;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * What this controller has written under its home since it started that the cluster does not share (Target 11): files
 * the store does not own, outside the categories that are local by design (caches, workspaces, plugins, local copies of
 * shared state, per-controller runtime files). Each is grouped by path pattern and, where the path names a class (root
 * {@code <class>.xml}), attributed to the plugin defining it. A list of what to check before relying on HA.
 */
@ExportedBean
@jenkins.security.stapler.StaplerAccessibleType // reached through ClusterView#getCompatibility
public final class HomeWrites {

    /** Local by design: never reported. */
    static final Pattern ALLOWED = Pattern.compile(String.join("|",
            "(plugins|war|logs|caches|workspace|tmp|updates|\\.cache|\\.groovy|\\.java|\\.m2|secrets)(/.*)?",
            "secret\\.key(\\.not-so-secret)?|identity\\.key\\.enc|cnj-import-report\\.txt|\\.lastStarted|\\.owner",
            "jenkins\\.install\\..*|queue\\.xml|nodeMonitors\\.xml|hudson\\.model\\.UpdateCenter\\.xml|.*FlowExecutionList\\.xml",
            "org\\.jenkins\\.plugins\\.lockableresources\\.LockableResourcesManager\\.xml",
            "users/users\\.xml",
            "(jobs/[^/]+/)*jobs/[^/]+/(builds|workspace[^/]*|running)(/.*)?",
            ".*\\.(tmp|log|lock)"));

    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private static final long STARTED = ManagementFactory.getRuntimeMXBean().getStartTime();

    private static volatile List<Finding> cached;
    private static volatile long cachedAt;

    /** One kind of unshared file. */
    @ExportedBean(defaultVisibility = 2)
    public static final class Finding {
        private final String pattern;
        private final String example;
        private final int count;
        private final String plugin;

        Finding(String pattern, String example, int count, String plugin) {
            this.pattern = pattern;
            this.example = example;
            this.count = count;
            this.plugin = plugin;
        }

        @Exported
        public String getPattern() {
            return pattern;
        }

        @Exported
        public String getExample() {
            return example;
        }

        @Exported
        public int getCount() {
            return count;
        }

        @Exported
        public String getPlugin() {
            return plugin;
        }
    }

    public hudson.model.Api getApi() {
        Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
        return new hudson.model.Api(this);
    }

    /** The controller answering. */
    @Exported
    public String getController() {
        return PostgresPersistenceStore.NAME;
    }

    /** The findings, rescanned at most every 30 s. */
    @Exported(inline = true)
    public List<Finding> getFindings() throws IOException {
        List<Finding> c = cached;
        if (c == null || System.currentTimeMillis() - cachedAt > 30_000) {
            c = scan();
            cached = c;
            cachedAt = System.currentTimeMillis();
        }
        return c;
    }

    /** Pattern of a key: numbers and item, user and node names become {@code *}. */
    static String pattern(String key) {
        String[] parts = key.split("/");
        for (int i = 0; i < parts.length; i++) {
            boolean named = i > 0 && (parts[i - 1].equals("jobs") || parts[i - 1].equals("users") || parts[i - 1].equals("nodes"));
            if (named || NUMBER.matcher(parts[i]).matches()) {
                parts[i] = "*";
            }
        }
        return String.join("/", parts);
    }

    static boolean reportable(String key, @CheckForNull PersistenceStore store) {
        return !ALLOWED.matcher(key).matches() && (store == null || !store.owns(key));
    }

    static List<Finding> scan() throws IOException {
        Path home = Jenkins.get().getRootDir().toPath();
        PersistenceStore store = PersistenceStores.get();
        Map<String, List<String>> byPattern = new TreeMap<>();
        Files.walkFileTree(home, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String key = home.relativize(dir).toString().replace(File.separatorChar, '/');
                return !key.isEmpty() && ALLOWED.matcher(key).matches() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String key = home.relativize(file).toString().replace(File.separatorChar, '/');
                if (attrs.isRegularFile() && attrs.lastModifiedTime().toMillis() >= STARTED && reportable(key, store)) {
                    byPattern.computeIfAbsent(pattern(key), k -> new ArrayList<>()).add(key);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // e.g. deleted meanwhile
            }
        });
        List<Finding> result = new ArrayList<>();
        byPattern.forEach((p, keys) -> result.add(new Finding(p, keys.get(0), keys.size(), pluginOf(keys.get(0)))));
        return result;
    }

    /** The plugin defining the class a root {@code <class>.xml} is named after, if any. */
    @CheckForNull
    static String pluginOf(String key) {
        if (key.contains("/") || !key.endsWith(".xml")) {
            return null;
        }
        try {
            Class<?> c = Jenkins.get().getPluginManager().uberClassLoader.loadClass(key.substring(0, key.length() - 4));
            PluginWrapper w = Jenkins.get().getPluginManager().whichPlugin(c);
            return w == null ? "core" : w.getShortName();
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
