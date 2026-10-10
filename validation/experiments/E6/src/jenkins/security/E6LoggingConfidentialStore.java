package jenkins.security;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * E6 spike: can a ConfidentialStore be supplied from outside jenkins-core?
 * Lives in package jenkins.security only because ConfidentialStore#secureRandom() is package-private.
 * Keeps keys in memory (deliberately not persisted) and logs every access.
 */
public class E6LoggingConfidentialStore extends ConfidentialStore {
    private static final Logger LOGGER = Logger.getLogger(E6LoggingConfidentialStore.class.getName());
    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> keys = new ConcurrentHashMap<>();

    public E6LoggingConfidentialStore() {
        LOGGER.warning("E6: external ConfidentialStore instantiated by " + getClass().getClassLoader());
    }

    @Override
    protected void store(ConfidentialKey key, byte[] payload) throws IOException {
        LOGGER.warning("E6: store " + key.getId());
        keys.put(key.getId(), payload.clone());
    }

    @Override
    protected byte[] load(ConfidentialKey key) throws IOException {
        LOGGER.warning("E6: load " + key.getId());
        byte[] v = keys.get(key.getId());
        return v == null ? null : v.clone();
    }

    @Override
    SecureRandom secureRandom() {
        return random;
    }

    @Override
    public byte[] randomBytes(int size) {
        byte[] b = new byte[size];
        random.nextBytes(b);
        return b;
    }
}
