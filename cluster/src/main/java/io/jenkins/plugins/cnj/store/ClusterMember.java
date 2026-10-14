package io.jenkins.plugins.cnj.store;

/**
 * This controller as a member of the cluster: what it is called, how the others reach it, what it runs, and what it
 * reports. A {@link ClusterStore} publishes these with each heartbeat.
 */
public final class ClusterMember {

    private ClusterMember() {}

    /** This controller's name, as other controllers see it (and as the prefix of its bound object ids). */
    public static final String NAME = env("CNJ_NAME", hostName());

    /** How other controllers reach this one directly, e.g. to forward requests for objects only it holds. */
    public static final String INTERNAL_URL = env("CNJ_INTERNAL_URL", "http://" + hostName() + ":8080/");

    /**
     * The version of the cluster protocol this controller speaks: what it stores and expects of its peers.
     * Raised when a change cannot coexist with older peers (which then must be gone before this version starts).
     */
    public static final int PROTOCOL = 2; // 2 (Target 15): writes jenkins_store.size (SchemaMigrations.STORE_SIZE)

    /** What this controller runs: core and plugin versions, shown in the cluster view. */
    public static final String VERSION = jenkins.model.Jenkins.VERSION + " / " + java.util.Objects.requireNonNullElse(
            ClusterMember.class.getPackage().getImplementationVersion(), "dev");

    /** Peers older than this protocol must not be alive when this controller starts (Target 10). */
    public static final int MINIMUM_PEER_PROTOCOL = Integer.getInteger(ClusterMember.class.getPackageName() + ".minimumPeerProtocol", 0);

    /** How long a starting controller waits for its persistence store (and blob store) before failing to start. */
    public static final long STARTUP_WAIT_SECONDS = Long.getLong(ClusterMember.class.getPackageName() + ".startupWaitSeconds", 600);

    /** What this controller has applied of the cluster's plugin set ({@link ClusterPlugins}), published with each heartbeat. */
    public static volatile String pluginsStatus;

    /** What else this controller reports to the cluster (JSON, {@link MemberStatus}), published with each heartbeat. */
    public static volatile String memberInfo;

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            return "localhost";
        }
    }
}
