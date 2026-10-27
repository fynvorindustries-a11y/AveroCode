package com.averolauncher.rank;

/** Reads presence signals for a running game process. Implementations must never throw. */
public interface ActivityProbe extends AutoCloseable {
    ActivitySample sample(long gamePid);

    @Override
    default void close() { }
}
