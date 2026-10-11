package io.jenkins.plugins.cnj.store;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.PluginServletFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.persistence.PersistenceStores;

/**
 * Refuses changes to the plugin set from the UI and REST API (install, upload, uninstall, enable, disable, upgrade,
 * downgrade) while the cluster store is in use.
 *
 * <p>Every controller of a cluster must run the same plugins: a plugin installed through the Plugins page lands only on
 * the controller which served that request, and is gone when its ephemeral home is (a restart). Requests that reach
 * another controller then miss the plugin's classes, descriptors and pages (e.g. a form whose parts 404). Plugins
 * belong in the controller image; a new set is rolled out as an upgrade. Set
 * {@code -Dio.jenkins.plugins.cnj.store.FixedPluginSet.allowChanges=true} to allow changes anyway.
 */
public final class FixedPluginSet implements Filter {

    private static final Logger LOGGER = Logger.getLogger(FixedPluginSet.class.getName());
    static final boolean ALLOW_CHANGES = Boolean.getBoolean(FixedPluginSet.class.getName() + ".allowChanges");
    static final Pattern CHANGES = Pattern.compile("^(?:/manage)?/(?:"
            + "pluginManager/(?:install|installPlugins|installNecessaryPlugins|uploadPlugin)"
            + "|pluginManager/plugin/[^/]+/(?:makeEnabled|makeDisabled|doUninstall)"
            + "|updateCenter/(?:upgrade|downgrade)"
            + ")/?$");
    static final String MESSAGE = "The plugin set of a cloud-native Jenkins cluster is fixed: plugins are part of the"
            + " controller image, so that every controller runs the same ones. Add the plugin to the image and roll it out"
            + " (helm upgrade) instead.";

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        if (!ALLOW_CHANGES) {
            PluginServletFilter.addFilter(new FixedPluginSet());
        }
    }

    static boolean isChange(String method, String path) {
        return "POST".equals(method) && CHANGES.matcher(path).matches();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest req
                && response instanceof HttpServletResponse rsp
                && PersistenceStores.get() instanceof PostgresPersistenceStore
                && isChange(req.getMethod(), req.getRequestURI().substring(req.getContextPath().length()))) {
            LOGGER.info(() -> "Refused " + req.getRequestURI() + ": the plugin set is fixed");
            rsp.setStatus(HttpServletResponse.SC_CONFLICT);
            rsp.setContentType("text/plain;charset=UTF-8");
            rsp.getOutputStream().write(MESSAGE.getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
