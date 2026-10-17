package io.jenkins.plugins.cluster;

import io.jenkins.plugins.cluster.spi.ClusterStore;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.PluginServletFilter;
import hudson.util.VersionNumber;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.persistence.PersistenceStores;

/**
 * What the plugin manager may change while the cluster store is in use, set with
 * {@code -Dio.jenkins.plugins.cluster.ClusterPluginSet.mode}:
 * <ul>
 * <li>{@code cluster} (the default): installs, upgrades, uninstalls, disables and enables apply to every controller
 * ({@link ClusterPlugins}). Downgrades (which restore a local backup the cluster does not have) and changes to the store
 * plugin itself are refused, and so are upgrades of Jenkins itself (a new image).
 * <li>{@code fixed}: the plugin set is the image's; every change is refused, with a message pointing to the image.
 * <li>{@code local}: changes stay on the controller which served the request, as without a cluster (its other
 * controllers then differ until its home is gone).
 * </ul>
 */
public final class ClusterPluginSet implements Filter {

    private static final Logger LOGGER = Logger.getLogger(ClusterPluginSet.class.getName());

    enum Mode { cluster, fixed, local }

    static final Mode MODE = Mode.valueOf(System.getProperty(ClusterPluginSet.class.getName() + ".mode", "cluster").toLowerCase(Locale.ROOT));

    static final Pattern CHANGES = Pattern.compile("^(?:/manage)?/(?:"
            + "pluginManager/(?<install>install|installPlugins|installNecessaryPlugins|uploadPlugin)"
            + "|pluginManager/plugin/(?<plugin>[^/]+)/(?<action>makeEnabled|makeDisabled|doUninstall)"
            + "|updateCenter/(?<uc>upgrade|downgrade)"
            + "|(?:pluginManager/updates|updateCenter)/(?<restart>safeRestart|cancelRestart)"
            + ")/?$");
    static final String FIXED = "The plugin set of this Jenkins cluster is fixed: plugins are part of the controller"
            + " image, so that every controller runs the same ones. Add the plugin to the image and roll it out (helm upgrade) instead.";
    static final String DOWNGRADE = "Downgrades are not applied across a Jenkins cluster (they restore a backup only this"
            + " controller has). Uninstall the plugin, then install the older version.";
    static final String CORE_UPGRADE = "Jenkins itself is part of the controller image: upgrade it by rolling out a new image (helm upgrade).";
    static final String RESTART = "This Jenkins cluster restarts its controllers for plugin changes by itself, one at a"
            + " time, once each has the change (see Manage Jenkins > Cluster). Restarting a single controller from here is not needed.";
    static final String PROTECTED_CHANGE = "The cluster plugins (" + String.join(", ", new java.util.TreeSet<>(ClusterPlugins.PROTECTED)) + ") are part of the controller image and"
            + " cannot be changed at runtime.";

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        if (MODE != Mode.local) {
            PluginServletFilter.addFilter(new ClusterPluginSet());
        }
    }

    /** The change a request makes to the plugin set, or null if none. */
    record Change(String kind, @CheckForNull String plugin) {}

    @CheckForNull
    static Change changeOf(String method, String path) {
        if (!"POST".equals(method)) {
            return null;
        }
        Matcher m = CHANGES.matcher(path);
        if (!m.matches()) {
            return null;
        }
        if (m.group("install") != null) {
            return new Change("install", null);
        }
        if (m.group("uc") != null) {
            return new Change(m.group("uc"), null);
        }
        if (m.group("restart") != null) {
            return new Change("restart", null);
        }
        return new Change(m.group("action"), m.group("plugin"));
    }

    /** Why a change is refused in this mode, or null if it may go ahead. */
    @CheckForNull
    static String refusal(Mode mode, Change change, boolean clusterActive) {
        if (mode == Mode.local) {
            return null;
        }
        if (change.kind().equals("restart")) {
            // the progress page's "restart when done": outside the cluster's one-at-a-time restarts (Target 15)
            return mode == Mode.cluster && clusterActive ? RESTART : null;
        }
        if (mode == Mode.fixed || !clusterActive) {
            return FIXED;
        }
        if (change.kind().equals("downgrade")) {
            return DOWNGRADE;
        }
        if (change.kind().equals("upgrade")) {
            return CORE_UPGRADE;
        }
        if (ClusterPlugins.isProtected(change.plugin())) {
            return PROTECTED_CHANGE;
        }
        return null;
    }

    /**
     * Why installing a plugin archive is refused in this mode, or null if it may go ahead: as {@link #refusal} for an
     * install of that plugin, and a downgrade (a version older than the newest the cluster or this controller has) too,
     * since it would replace a newer version in the cluster's plugin set, and fresh homes would get the older one.
     *
     * @param newest the newest version known of that plugin: in the cluster's plugin set, or in {@code plugins/} here
     */
    @CheckForNull
    static String archiveRefusal(Mode mode, @NonNull String name, @CheckForNull String version, @CheckForNull String newest,
            boolean clusterActive) {
        String refusal = refusal(mode, new Change("install", name), clusterActive);
        if (refusal != null || mode == Mode.local) {
            return refusal;
        }
        if (version != null && newest != null && new VersionNumber(version).isOlderThan(new VersionNumber(newest))) {
            return name + " " + version + " is older than " + newest + ", which this Jenkins cluster has. " + DOWNGRADE;
        }
        return null;
    }

    /** The protected plugins a plugin manager install form ({@code plugin.<name>.<site>} parameters) asks for. */
    static java.util.List<String> protectedInForm(java.util.Collection<String> parameterNames) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String p : parameterNames) {
            if (p.startsWith("plugin.")) {
                String rest = p.substring("plugin.".length());
                int dot = rest.lastIndexOf('.');
                String name = dot == -1 ? rest : rest.substring(0, dot);
                if (ClusterPlugins.isProtected(name)) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req)
                || !(response instanceof HttpServletResponse rsp)
                || !(PersistenceStores.get() instanceof ClusterStore)) {
            chain.doFilter(request, response);
            return;
        }
        Change change = changeOf(req.getMethod(), req.getRequestURI().substring(req.getContextPath().length()));
        if (change == null) {
            chain.doFilter(request, response);
            return;
        }
        String refusal = refusal(MODE, change, ClusterPlugins.active());
        if (refusal == null && change.kind().equals("install") && req.getRequestURI().endsWith("/install")
                && String.valueOf(req.getContentType()).startsWith("application/x-www-form-urlencoded")
                && !protectedInForm(java.util.Collections.list(req.getParameterNames())).isEmpty()) {
            // the plugin manager's form (others, uploads and dependencies are refused when downloaded, by
            // ClusterUpdateCenterConfiguration#postValidate)
            refusal = PROTECTED_CHANGE;
        }
        if (refusal != null) {
            String why = refusal;
            LOGGER.info(() -> "Refused " + req.getRequestURI() + ": " + why);
            rsp.setStatus(HttpServletResponse.SC_CONFLICT);
            rsp.setContentType("text/plain;charset=UTF-8");
            rsp.getOutputStream().write(why.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (change.kind().equals("install") && MODE == Mode.cluster) {
            UpdateCenterRouting.installedHere(req, rsp); // before the response is committed (it redirects)
        }
        chain.doFilter(request, response);
        if (change.plugin() != null && rsp.getStatus() < 400) {
            switch (change.kind()) {
                case "doUninstall" -> ClusterPlugins.changed(change.plugin(), ClusterPlugins.State.uninstalled);
                case "makeDisabled" -> ClusterPlugins.changed(change.plugin(), ClusterPlugins.State.disabled);
                case "makeEnabled" -> ClusterPlugins.changed(change.plugin(), ClusterPlugins.State.installed);
                default -> {}
            }
        }
        // installs and upgrades are shared by ClusterUpdateCenterConfiguration once the update center has them
    }
}
