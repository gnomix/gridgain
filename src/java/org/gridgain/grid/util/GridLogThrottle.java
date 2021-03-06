// Copyright (C) GridGain Systems Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.util;

import org.gridgain.grid.lang.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.typedef.*;
import org.gridgain.grid.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.concurrent.*;

/**
 * Grid log throttle.
 * <p>
 * Errors are logged only if they were not logged for the last
 * {@link #throttleTimeout} number of minutes.
 * Note that not only error messages are checked for duplicates, but also exception
 * classes.
 *
 * @author 2012 Copyright (C) GridGain Systems
 * @version 4.0.2c.12042012
 */
public class GridLogThrottle {
    /** Default throttle timeout in milliseconds (value is <tt>5 * 60 * 1000</tt>). */
    public static final int DFLT_THROTTLE_TIMEOUT = 5 * 60 * 1000;

    /** Throttle timeout. */
    private static int throttleTimeout = DFLT_THROTTLE_TIMEOUT;

    /** Errors. */
    private static final ConcurrentMap<GridTuple2<Class<? extends Throwable>, String>, Long> errors =
        new ConcurrentHashMap<GridTuple2<Class<? extends Throwable>, String>, Long>();

    /**
     * Sets system-wide log throttle timeout.
     *
     * @param timeout System-wide log throttle timeout.
     */
    public static void throttleTimeout(int timeout) {
        throttleTimeout = timeout;
    }

    /**
     * Gets system-wide log throttle timeout.
     *
     * @return System-side log throttle timeout.
     */
    public static long throttleTimeout() {
        return throttleTimeout;
    }

    /**
     * Logs error if needed.
     *
     * @param log Logger.
     * @param e Error (optional).
     * @param msg Message.
     */
    public static void error(GridLogger log, @Nullable Throwable e, String msg) {
        assert log != null;
        assert !F.isEmpty(msg);

        log(log, e, msg, null, true);
    }

    /**
     * Logs warning if needed.
     *
     * @param log Logger.
     * @param e Error (optional).
     * @param msg Message.
     */
    public static void warn(GridLogger log, @Nullable Throwable e, String msg) {
        assert log != null;
        assert !F.isEmpty(msg);

        log(log, e, msg, null, false);
    }

    /**
     * Logs warning if needed.
     *
     * @param log Logger.
     * @param e Error (optional).
     * @param longMsg Long message (or just message).
     * @param shortMsg Short message for quite logging.
     */
    public static void warn(GridLogger log, @Nullable Throwable e, String longMsg, @Nullable String shortMsg) {
        assert log != null;
        assert !F.isEmpty(longMsg);

        log(log, e, longMsg, shortMsg, false);
    }

    /**
     * Clears all stored data. This will make throttle to behave like a new one.
     */
    public static void clear() {
        errors.clear();
    }

    /**
     * Logs message if needed using desired level.
     *
     * @param log Logger.
     * @param e Error (optional).
     * @param longMsg Long message (or just message).
     * @param shortMsg Short message for quite logging.
     * @param error If {@code true} ERROR level is used.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    private static void log(GridLogger log, @Nullable Throwable e, String longMsg, @Nullable String shortMsg,
        boolean error) {
        assert log != null;
        assert !F.isEmpty(longMsg);

        GridTuple2<Class<? extends Throwable>, String> tup =
            e != null ? F.<Class<? extends Throwable>, String>t(e.getClass(), e.getMessage()) :
                F.<Class<? extends Throwable>, String>t(null, longMsg);

        while (true) {
            Long loggedTs = errors.get(tup);

            long curTs = System.currentTimeMillis();

            if (loggedTs == null || loggedTs < curTs - throttleTimeout) {
                if (replace(tup, loggedTs, curTs)) {
                    if (error) {
                        if (e != null)
                            U.error(log, longMsg, e);
                        else
                            U.error(log, longMsg);
                    }
                    else
                        U.warn(log, longMsg, F.isEmpty(shortMsg) ? longMsg : shortMsg);

                    break;
                }
            }
            else
                // Ignore.
                break;
        }
    }

    /**
     * @param t Log throttle entry.
     * @param oldStamp Old timestamp, possibly {@code null}.
     * @param newStamp New timestamp.
     * @return {@code True} if throttle value was replaced.
     */
    private static boolean replace(GridTuple2<Class<? extends Throwable>, String> t, @Nullable Long oldStamp,
        Long newStamp) {
        assert newStamp != null;

        if (oldStamp == null) {
            Long old = errors.putIfAbsent(t, newStamp);

            return old == null;
        }

        return errors.replace(t, oldStamp, newStamp);
    }

    /** Ensure singleton. */
    protected GridLogThrottle() {
        // No-op.
    }
}
