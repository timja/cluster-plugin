package io.jenkins.plugins.cluster.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.model.Action;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import java.io.IOException;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The controllers of one cluster, each a JVM of its own ({@link RealJenkinsExtension}), sharing a PostgreSQL store and
 * an Azurite blob store, and killed, stopped and started as real ones are. Register it in place of the controllers'
 * own extensions:
 *
 * <pre>{@code
 * @RegisterExtension
 * private final RealCluster cluster = new RealCluster("a", "b");
 * }</pre>
 *
 * <p>Every test gets a cluster of its own: a new database and blob container, the controllers started in order, each
 * after the first with the first's keys (as the chart's seed Secret gives every pod the same). The containers are
 * started once and shared by every test of the JVM.
 *
 * <p>The shared queue gives a build to whichever controller claims it first, wherever it was scheduled: a test finds
 * where a build runs with {@link #owner} before killing that controller. A build is named by its job's full name and
 * its number.
 */
public final class RealCluster implements BeforeEachCallback, AfterEachCallback {

    /** Azurite's well-known development account key. */
    private static final String AZURITE_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(RealCluster.class);

    private static final AtomicInteger clusters = new AtomicInteger();

    private final List<RealJenkinsExtension> controllers = new ArrayList<>();

    private final List<RealJenkinsExtension.Step> onStart = new ArrayList<>();

    private Containers containers;

    private String database;

    /** Controllers by name, started in this order. */
    public RealCluster(String... names) {
        for (String name : names) {
            controllers.add(new RealJenkinsExtension()
                    .withName(name)
                    .withPort(freePort())
                    .withTimeout((int) TimeUnit.MINUTES.toSeconds(20))
                    .javaOptions("-Xmx768m", "-Dio.jenkins.plugins.cluster.startupWaitSeconds=60"));
        }
    }

    /** Configures every controller, such as with a logger, before the test. */
    public RealCluster configure(Consumer<RealJenkinsExtension> configuration) {
        controllers.forEach(configuration);
        return this;
    }

    /**
     * Runs on a controller each time it has started, as configuration as code would on every controller (that is not
     * shared through the store).
     */
    public RealCluster onStart(RealJenkinsExtension.Step step) {
        onStart.add(step);
        return this;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** The PostgreSQL and Azurite containers of the JVM, stopped once every test has run. */
    @SuppressWarnings("deprecation") // CloseableResource as well as AutoCloseable, for JUnit 5 and 6
    private static final class Containers implements ExtensionContext.Store.CloseableResource, AutoCloseable {
        final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
        final GenericContainer<?> azurite = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.34.0")
                .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
                .withExposedPorts(10000);
        private boolean closed;

        Containers() {
            postgres.start();
            azurite.start();
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                azurite.stop();
                postgres.stop();
            }
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        containers = context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(Containers.class, k -> new Containers(), Containers.class);
        for (RealJenkinsExtension c : controllers) {
            c.beforeEach(context);
        }
        database = "cluster" + clusters.incrementAndGet(); // also the blob container's name: 3 to 63 characters
        try (Connection c = containers.postgres.createConnection("");
                Statement s = c.createStatement()) {
            s.execute("create database " + database);
        }
        String blobs = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=" + AZURITE_KEY
                + ";BlobEndpoint=http://" + containers.azurite.getHost() + ":"
                + containers.azurite.getMappedPort(10000) + "/devstoreaccount1;";
        for (RealJenkinsExtension c : controllers) {
            c.extraEnv("CLUSTER_POSTGRES_URL", jdbcUrl())
                    .extraEnv("CLUSTER_POSTGRES_USER", containers.postgres.getUsername())
                    .extraEnv("CLUSTER_POSTGRES_PASSWORD", containers.postgres.getPassword())
                    .extraEnv("CLUSTER_AZURE_BLOB_CONNECTION_STRING", blobs)
                    .extraEnv("CLUSTER_AZURE_BLOB_CONTAINER", database)
                    .extraEnv("JENKINS_CLUSTER_NAME", c.getName())
                    .extraEnv("JENKINS_CLUSTER_INTERNAL_URL", c.getUrl().toString());
        }
        RealJenkinsExtension first = controllers.get(0);
        try {
            for (RealJenkinsExtension c : controllers) {
                if (c != first) {
                    seedKeys(first.getHome().toPath(), c.getHome().toPath());
                }
                start(c);
            }
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        List<RealJenkinsExtension> reversed = new ArrayList<>(controllers);
        Collections.reverse(reversed);
        Exception failure = null;
        for (RealJenkinsExtension c : reversed) {
            try {
                c.afterEach(context);
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Every controller of a cluster has the same key material. */
    private static void seedKeys(Path from, Path to) throws IOException {
        for (String f : List.of("secret.key", "secret.key.not-so-secret", "identity.key.enc")) {
            if (Files.exists(from.resolve(f))) {
                Files.copy(from.resolve(f), to.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path secrets = from.resolve("secrets");
        if (!Files.isDirectory(secrets)) {
            return;
        }
        try (Stream<Path> files = Files.walk(secrets)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ------------------------------------------------------------------ controllers

    /** The controllers, in the order they were named. */
    public List<RealJenkinsExtension> controllers() {
        return Collections.unmodifiableList(controllers);
    }

    public RealJenkinsExtension controller(String name) {
        for (RealJenkinsExtension c : controllers) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("No controller " + name);
    }

    /** The controllers whose JVM runs. */
    public List<RealJenkinsExtension> alive() {
        List<RealJenkinsExtension> alive = new ArrayList<>();
        for (RealJenkinsExtension c : controllers) {
            if (c.isAlive()) {
                alive.add(c);
            }
        }
        return alive;
    }

    /** The controller named after this one (with two, the other one). */
    public RealJenkinsExtension next(RealJenkinsExtension c) {
        return controllers.get((controllers.indexOf(c) + 1) % controllers.size());
    }

    /** Starts a controller, again after it was stopped or killed as its pod would be: same home, same name. */
    public void start(RealJenkinsExtension c) throws Throwable {
        c.startJenkins();
        for (RealJenkinsExtension.Step step : onStart) {
            c.runRemotely(step);
        }
    }

    /** Stops a controller cleanly (its JVM exits) and starts it again: a rolling update of that controller. */
    public void restart(RealJenkinsExtension c) throws Throwable {
        c.stopJenkins();
        start(c);
    }

    /** Kills a controller's JVM, as a node lost would. */
    public void kill(RealJenkinsExtension c) throws Throwable {
        c.stopJenkinsForcibly();
    }

    // ------------------------------------------------------------------ the store

    public String jdbcUrl() {
        return "jdbc:postgresql://" + containers.postgres.getHost() + ":" + containers.postgres.getMappedPort(5432)
                + "/" + database;
    }

    /** The first column of every row of a query of the store's database. */
    public List<String> query(String sql, String... parameters) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                        jdbcUrl(), containers.postgres.getUsername(), containers.postgres.getPassword());
                PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setString(i + 1, parameters[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
        }
        return rows;
    }

    /** The store's keys starting with a prefix, in order. */
    public List<String> keys(String prefix) throws Exception {
        String like = prefix.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%") + "%";
        return query("select key from jenkins_store where key like ? order by key", like);
    }

    /** The kinds of the store's events for a key (WRITTEN, DELETED), oldest first. */
    public List<String> events(String key) throws Exception {
        return query("select kind from jenkins_store_events where key = ? order by seq", key);
    }

    // ------------------------------------------------------------------ jobs and builds

    /**
     * Creates a job on the first controller, by a step given its name, and waits until every controller that runs
     * knows it.
     */
    public void create(String job, RealJenkinsExtension.StepWithOneArg<String> creator) throws Throwable {
        controllers.get(0).runRemotely(creator, job);
        awaitEverywhere(job);
    }

    /** Creates a sandboxed Pipeline job. */
    public void pipeline(String job, String script) throws Throwable {
        controllers.get(0).runRemotely(RealCluster::createPipeline, job, script);
        awaitEverywhere(job);
    }

    private void awaitEverywhere(String job) throws Throwable {
        for (RealJenkinsExtension c : alive()) {
            await(job + " to reach " + c.getName(), 30, () -> c.runRemotely(RealCluster::hasJob, job));
        }
    }

    /** Schedules a build from a controller; the shared queue decides where it runs. */
    public void schedule(RealJenkinsExtension c, String job) throws Throwable {
        c.runRemotely(RealCluster::scheduleHere, job, "", "");
    }

    /** Schedules a build with a string parameter (builds with different parameters are not folded in the queue). */
    public void schedule(RealJenkinsExtension c, String job, String parameter, String value) throws Throwable {
        c.runRemotely(RealCluster::scheduleHere, job, parameter, value);
    }

    /** The controller whose JVM runs the build, or null. */
    public RealJenkinsExtension owner(String job, int n) {
        for (RealJenkinsExtension c : alive()) {
            try {
                if (c.runRemotely(RealCluster::runsHere, job, n)) {
                    return c;
                }
            } catch (Throwable t) {
                // going down: not the owner
            }
        }
        return null;
    }

    public RealJenkinsExtension awaitOwner(String job, int n) throws Throwable {
        RealJenkinsExtension[] found = new RealJenkinsExtension[1];
        await(job + "#" + n + " to run somewhere", 120, () -> (found[0] = owner(job, n)) != null);
        return found[0];
    }

    /** The build's result, from any controller that knows it ended, or null. */
    public String result(String job, int n) throws Throwable {
        for (RealJenkinsExtension c : alive()) {
            String x = result(c, job, n);
            if (x != null) {
                return x;
            }
        }
        return null;
    }

    /** The build's result as one controller sees it, or null if it has none (yet). */
    public String result(RealJenkinsExtension c, String job, int n) throws Throwable {
        return c.runRemotely(RealCluster::resultHere, job, n);
    }

    public void awaitResult(String job, int n, String result, int seconds) throws Throwable {
        await(job + "#" + n + " to end", seconds, () -> result(job, n) != null);
        assertEquals(result, result(job, n), job + "#" + n + ":\n" + log(job, n));
    }

    /** The longest console of the build any controller has: the owner's while it runs, any once it ended. */
    public String log(String job, int n) throws Throwable {
        String longest = "";
        for (RealJenkinsExtension c : alive()) {
            String x = c.runRemotely(RealCluster::logHere, job, n);
            if (x.length() > longest.length()) {
                longest = x;
            }
        }
        return longest;
    }

    public boolean logHas(String job, int n, String text) throws Throwable {
        return log(job, n).contains(text);
    }

    public void awaitLog(String job, int n, String text, int seconds) throws Throwable {
        await(job + "#" + n + " to print \"" + text + "\"", seconds, () -> logHas(job, n, text));
    }

    /** How many lines of the build's console are this line, or start with it and a space. */
    public int count(String job, int n, String line) throws Throwable {
        return (int) log(job, n)
                .lines()
                .filter(l -> l.equals(line) || l.startsWith(line + " "))
                .count();
    }

    // ------------------------------------------------------------------ run on a controller

    private static void createPipeline(JenkinsRule r, String job, String script) throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, job);
        p.setDefinition(new CpsFlowDefinition(script, true));
    }

    private static Boolean hasJob(JenkinsRule r, String job) {
        return r.jenkins.getItemByFullName(job, Job.class) != null;
    }

    private static void scheduleHere(JenkinsRule r, String job, String parameter, String value) {
        Job<?, ?> j = r.jenkins.getItemByFullName(job, Job.class);
        if (!(j instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> p)) {
            throw new IllegalArgumentException(job + " cannot be scheduled");
        }
        Action[] actions = parameter.isEmpty()
                ? new Action[0]
                : new Action[] {new ParametersAction(new StringParameterValue(parameter, value))};
        p.scheduleBuild2(0, actions);
    }

    private static Run<?, ?> run(JenkinsRule r, String job, int n) {
        Job<?, ?> j = r.jenkins.getItemByFullName(job, Job.class);
        return j == null ? null : j.getBuildByNumber(n);
    }

    private static Boolean runsHere(JenkinsRule r, String job, Integer n) {
        Run<?, ?> run = run(r, job, n);
        return run != null && run.isBuilding() && RunningBuilds.owns(run);
    }

    private static String resultHere(JenkinsRule r, String job, Integer n) {
        Run<?, ?> run = run(r, job, n);
        return run == null || run.isBuilding() || run.getResult() == null
                ? null
                : run.getResult().toString();
    }

    private static String logHere(JenkinsRule r, String job, Integer n) throws IOException {
        Run<?, ?> run = run(r, job, n);
        if (run == null) {
            return "";
        }
        StringWriter w = new StringWriter();
        run.getLogText().writeLogTo(0, w);
        return w.toString();
    }

    // ------------------------------------------------------------------ waiting

    @FunctionalInterface
    public interface Check {
        boolean holds() throws Throwable;
    }

    /** Waits until a check holds, taking a failure as not yet (a controller starting or going down). */
    public static void await(String what, int seconds, Check check) throws Throwable {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Throwable last = null;
        while (true) {
            try {
                if (check.holds()) {
                    return;
                }
            } catch (Throwable t) {
                last = t;
            }
            if (System.nanoTime() > deadline) {
                AssertionError e = new AssertionError("Timed out after " + seconds + " s waiting for " + what);
                if (last != null) {
                    e.addSuppressed(last);
                }
                throw e;
            }
            Thread.sleep(1000);
        }
    }
}
