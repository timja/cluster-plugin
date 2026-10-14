package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Controllers: who is alive, how to reach them, and whether this one may have looked gone to the others. */
public interface Membership {
    /** Every controller known, newest first. */
    @NonNull
    List<Member> members() throws IOException;

    /** The origins of the live controllers. */
    @NonNull
    Set<String> aliveMembers() throws IOException;

    /** The internal URL of a live controller, by name, or null if no live controller has that name. */
    @CheckForNull
    String urlOf(@NonNull String name) throws IOException;

    /** The internal URL of a live controller, by origin, or null. */
    @CheckForNull
    String urlOfOrigin(@NonNull String origin) throws IOException;

    /** Live controllers other than this one speaking a protocol older than {@code minimum}, as {@code name (protocol N)}. */
    @NonNull
    List<String> peersBelow(int minimum) throws IOException;

    /** Whether this controller's membership may have lapsed (heartbeats missing for longer than the member timeout). */
    boolean mayHaveLapsed();

    /** How many times this controller's membership may have lapsed since it started. */
    long lapseCount();

    /** Whether this controller's view of who is alive can be trusted yet (heartbeats steady for a member timeout). */
    boolean canJudgeLiveness();

    /** The placeholders of builds running anywhere, by key (for the cluster view). */
    @NonNull
    Map<String, byte[]> runningPlaceholders() throws IOException;

    /** Background maintenance of the store's own data (e.g. schema migrations) and how far it got, for the cluster view. */
    @NonNull
    default List<Migration> migrations() throws IOException {
        return List.of();
    }

    /** A controller of the cluster, alive or gone (shown in the cluster view). */
    record Member(String origin, String name, String url, long heartbeatAt, long startedAt, boolean alive, String version, int protocol,
            String plugins, String info) {}

    /** A background migration of the store's data. */
    record Migration(String id, String state, String owner, long rows) {}
}

