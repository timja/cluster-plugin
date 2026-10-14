package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Which controller holds each agent's channel. */
public interface Channels {
    void channelConnected(@NonNull String agent) throws IOException;

    void channelDisconnected(@NonNull String agent) throws IOException;

    /** The name and internal URL of the controller holding the agent's channel, or null. */
    @CheckForNull
    String[] channelHolder(@NonNull String agent) throws IOException;

    /** Every channel: agent to controller name. */
    @NonNull
    Map<String, String> channels() throws IOException;
}
