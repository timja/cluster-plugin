package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import jenkins.websocket.WebSocketSession;
import jenkins.websocket.WebSockets;
import org.kohsuke.stapler.HttpResponse;

/**
 * For experiments only (Target 15): a WebSocket echo under every build's URL, {@code job/<name>/<N>/cnj-ws-echo/},
 * answering with this controller's name, to check that WebSocket requests for a build running elsewhere are relayed
 * to its owner. Off unless {@code -Dio.jenkins.plugins.cnj.store.WebSocketEcho.enabled=true}; administrators only.
 */
public final class WebSocketEcho implements Action {

    static final boolean ENABLED = Boolean.getBoolean(WebSocketEcho.class.getName() + ".enabled");

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "cnj-ws-echo";
    }

    public HttpResponse doIndex() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return WebSockets.upgrade(new WebSocketSession() {
            @Override
            protected void text(String message) throws IOException {
                sendText(message + " from " + PostgresPersistenceStore.NAME);
            }
        });
    }

    @Extension
    public static final class Factory extends TransientActionFactory<Run> {
        @Override
        public Class<Run> type() {
            return Run.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Run target) {
            return ENABLED ? List.of(new WebSocketEcho()) : List.of();
        }
    }
}
