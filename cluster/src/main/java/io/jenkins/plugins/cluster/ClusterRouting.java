package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import jenkins.persistence.LocalFiles;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.SharedFiles;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The files the cluster shares beyond core's default set, and those it keeps per controller, as inputs of core's
 * routing; and an approximation of that routing for diagnostics (which local writes are not shared).
 */
@Restricted(NoExternalUse.class)
public final class ClusterRouting {

    private ClusterRouting() {}

    /** Branch indexing state, so that whichever controller indexes compares against the same revisions. */
    @Extension
    public static final class Shared implements SharedFiles {
        @NonNull
        @Override
        public Collection<String> patterns() {
            return List.of("jobs/**/scm-revision-hash.xml", "jobs/**/scm-last-seen-revision-hash.xml");
        }
    }

    /** Runtime state each controller keeps for itself: the Pipelines it runs. */
    @Extension
    public static final class Local implements LocalFiles {
        @NonNull
        @Override
        public Collection<String> patterns() {
            return List.of("*FlowExecutionList.xml");
        }
    }

    /** Whether the store holds the file at this key (relative to {@code JENKINS_HOME}), as far as can be told here. */
    public static boolean isShared(@NonNull String key) {
        PersistenceStore store = PersistenceStores.get();
        if (store == null || key.startsWith("_")) {
            return false;
        }
        Path path = Path.of(key);
        for (LocalFiles local : ExtensionList.lookup(LocalFiles.class)) {
            if (matches(local.patterns(), path)) {
                return false;
            }
        }
        if (inDefaultSet(key, store)) {
            return true;
        }
        for (SharedFiles shared : ExtensionList.lookup(SharedFiles.class)) {
            if (matches(shared.patterns(), path)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a key is in core's default shared set (as {@link SharedFiles} describes it). */
    static boolean inDefaultSet(@NonNull String key, @NonNull PersistenceStore store) {
        String[] segments = key.split("/");
        if (segments[0].equals("jobs")) {
            if (!key.endsWith("/config.xml")) {
                return false;
            }
            for (String s : segments) {
                if (s.equals("builds")) {
                    return false;
                }
            }
            return true;
        }
        if (key.equals("config.xml")) {
            return true;
        }
        if (segments.length == 2 && segments[0].equals("secrets")) {
            return store.storesConfidentialKeys();
        }
        if (!Boolean.parseBoolean(System.getProperty("jenkins.persistence.StoreRouting.staticState", "true"))) {
            return false;
        }
        return segments.length == 1 && key.endsWith(".xml")
                || segments.length == 3
                        && (segments[0].equals("users") || segments[0].equals("nodes"))
                        && segments[2].equals("config.xml")
                || segments.length == 2 && segments[0].equals("log") && key.endsWith(".xml");
    }

    private static boolean matches(Collection<String> patterns, Path path) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String p : patterns) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
        }
        return matchers.stream().anyMatch(m -> m.matches(path));
    }
}
