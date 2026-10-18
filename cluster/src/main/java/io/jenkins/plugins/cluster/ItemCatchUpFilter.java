package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.PluginServletFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;

/**
 * Lets a request for an item just made on another controller find it here: behind a load balancer without sticky
 * sessions, a client creating a job and then building it ({@code POST /createItem}, then {@code POST /job/x/build}) may
 * reach a controller which has not applied the creation yet, a moment's lag (the store's notifications), and got a 404.
 *
 * <p>When a path names an item ({@code [/view/v…]/job/a[/job/b…][/…]}) this controller does not have but the store
 * does, the request waits, at most {@link #WAIT_MILLIS}, for the change to be applied here. Requests for items which
 * exist here, and for items the store does not have either, cost nothing more than the lookup.
 */
public final class ItemCatchUpFilter implements Filter {

    private static final Logger LOGGER = Logger.getLogger(ItemCatchUpFilter.class.getName());

    /** How long a request waits for an item stored but not yet applied here. */
    static final long WAIT_MILLIS = Long.getLong(ItemCatchUpFilter.class.getName() + ".waitMillis", 5000);

    private static final AtomicLong waited = new AtomicLong();

    /** Requests that waited for an item to be applied here, for diagnostics. */
    public static long getWaitedCount() {
        return waited.get();
    }

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        PluginServletFilter.addFilter(new ItemCatchUpFilter());
    }

    /** The full names of the items a path goes through, outermost first: {@code /job/f/job/p/…} gives {@code f, f/p}. */
    @NonNull
    static List<String> itemNames(@NonNull String path) {
        String[] segments = path.split("/");
        int i = 0;
        while (i < segments.length && segments[i].isEmpty()) {
            i++;
        }
        while (i + 1 < segments.length && segments[i].equals("view")) {
            i += 2;
        }
        List<String> names = new ArrayList<>();
        String prefix = "";
        while (i + 1 < segments.length && segments[i].equals("job")) {
            String name = URLDecoder.decode(segments[i + 1].replace("+", "%2B"), StandardCharsets.UTF_8);
            if (name.isEmpty() || name.contains("/")) {
                break;
            }
            prefix = prefix.isEmpty() ? name : prefix + "/" + name;
            names.add(prefix);
            i += 2;
        }
        return names;
    }

    /** The store key of an item's configuration: {@code f/p} is {@code jobs/f/jobs/p/config.xml}. */
    @NonNull
    static String configKey(@NonNull String fullName) {
        return "jobs/" + fullName.replace("/", "/jobs/") + "/config.xml";
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest req && PersistenceStores.get() != null) {
            // outermost first: a folder and a job in it made together are applied in that order
            for (String name :
                    itemNames(req.getRequestURI().substring(req.getContextPath().length()))) {
                if (!existsHere(name) && !awaitApplied(name)) {
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean existsHere(@NonNull String fullName) {
        // whether the item exists, whoever asks: the request's own permissions apply when it is answered
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Jenkins.get().getItemByFullName(fullName) != null;
        }
    }

    /** Waits for an item stored but not here to be applied here; whether it now is. */
    private static boolean awaitApplied(@NonNull String fullName) {
        PersistenceStore store = PersistenceStores.get();
        try {
            if (store == null || store.read(configKey(fullName)) == null) {
                return false; // not made anywhere either
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not check the store for " + fullName, e);
            return false;
        }
        waited.incrementAndGet();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        try {
            while (System.nanoTime() < deadline) {
                Thread.sleep(50);
                if (existsHere(fullName)) {
                    LOGGER.fine(() -> "Waited for " + fullName + ", made elsewhere, to be applied here");
                    return true;
                }
            }
            LOGGER.fine(() -> fullName + " is stored but was not applied here within " + WAIT_MILLIS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
