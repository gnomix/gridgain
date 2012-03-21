// Copyright (C) GridGain Systems Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.spi.discovery.tcp.ipfinder.sharedfs;

import org.gridgain.grid.editions.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.*;
import org.gridgain.grid.typedef.*;
import org.gridgain.grid.typedef.internal.*;
import org.gridgain.grid.util.tostring.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Shared filesystem-based IP finder.
 * <h1 class="header">Configuration</h1>
 * <h2 class="header">Mandatory</h2>
 * There are no mandatory configuration parameters.
 * <h2 class="header">Optional</h2>
 * <ul>
 *      <li>Path (see {@link #setPath(String)})</li>
 *      <li>Shared flag (see {@link #setShared(boolean)})</li>
 * </ul>
 * <p>
 * If {@link #getPath()} is not provided, then {@link #DFLT_PATH} will be used and
 * only local nodes will discover each other. To enable discovery over network
 * you must provide a path to a shared directory explicitly.
 * <p>
 * The directory will contain empty files named like the following 192.168.1.136#1001.
 * <p>
 * Note that this finder is shared by default (see {@link GridTcpDiscoveryIpFinder#isShared()}.
 *
 * @author 2012 Copyright (C) GridGain Systems
 * @version 4.0.0c.21032012
 */
public class GridTcpDiscoverySharedFsIpFinder extends GridTcpDiscoveryIpFinderAdapter {
    /** Default path for local testing only. */
    public static final String DFLT_PATH = "work/disco/tcp";

    /** Delimiter to use between address and port tokens in file names. */
    public static final String DELIM = "#";

    /** Grid logger. */
    @GridLoggerResource
    private GridLogger log;

    /** File-system path. */
    private String path = DFLT_PATH;

    /** Folder to keep items in. */
    @GridToStringExclude
    private File folder;

    /** Warning guard. */
    @GridToStringExclude
    private final AtomicBoolean warnGuard = new AtomicBoolean();

    /** Init guard. */
    @GridToStringExclude
    private final AtomicBoolean initGuard = new AtomicBoolean();

    /** Init latch. */
    @GridToStringExclude
    private final CountDownLatch initLatch = new CountDownLatch(1);

    /**
     * Constructor.
     */
    public GridTcpDiscoverySharedFsIpFinder() {
        setShared(true);
    }

    /**
     * Gets path.
     *
     * @return Shared path.
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets path.
     *
     * @param path Shared path.
     */
    @GridSpiConfiguration(optional = true)
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Initializes folder to work with.
     *
     * @return Folder.
     * @throws GridSpiException If failed.
     */
    private File initFolder() throws GridSpiException {
        if (initGuard.compareAndSet(false, true)) {
            if (path == null)
                throw new GridSpiException("Shared file system path is null " +
                    "(it should be configured via setPath(..) configuration property).");

            if (path.equals(DFLT_PATH) && warnGuard.compareAndSet(false, true))
                U.warn(log, "Default local computer-only share is used by IP finder.");

            try {
                URL folderUrl = U.resolveGridGainUrl(path);

                if (folderUrl == null)
                    throw new GridSpiException("Failed to resolve path: " + path);

                File tmp;

                try {
                    tmp = new File(folderUrl.toURI());
                }
                catch (URISyntaxException e) {
                    throw new GridSpiException("Failed to resolve path: " + path, e);
                }

                if (!tmp.isDirectory())
                    throw new GridSpiException("Failed to initialize shared file system path " +
                        "(path must point to folder): " + path);

                if (!tmp.canRead() || !tmp.canWrite())
                    throw new GridSpiException("Failed to initialize shared file system path " +
                        "(path must be readable and writable): " + path);

                folder = tmp;
            }
            finally {
                initLatch.countDown();
            }
        }
        else {
            try {
                initLatch.await();
            }
            catch (InterruptedException e) {
                throw new GridSpiException("Thread has been interrupted.", e);
            }

            if (folder == null)
                throw new GridSpiException("Failed to initialize shared file system folder (check logs for errors).");
        }

        return folder;
    }

    /** {@inheritDoc} */
    @Override public Collection<InetSocketAddress> getRegisteredAddresses() throws GridSpiException {
        initFolder();

        Collection<InetSocketAddress> addrs = new LinkedList<InetSocketAddress>();

        for (String fileName : folder.list())
            if (!".svn".equals(fileName)) {
                InetSocketAddress addr = null;

                StringTokenizer st = new StringTokenizer(fileName, DELIM);

                if (st.countTokens() == 2) {
                    String addrStr = st.nextToken();
                    String portStr = st.nextToken();

                    try {
                        int port = Integer.parseInt(portStr);

                        addr = new InetSocketAddress(addrStr, port);
                    }
                    catch (NumberFormatException e) {
                        U.error(log, "Failed to parse file entry: " + fileName, e);
                    }
                    catch (IllegalArgumentException e) {
                        U.error(log, "Failed to parse file entry: " + fileName, e);
                    }
                }

                if (addr != null)
                    addrs.add(addr);
            }

        return Collections.unmodifiableCollection(addrs);
    }

    /** {@inheritDoc} */
    @Override public void registerAddresses(Collection<InetSocketAddress> addrs) throws GridSpiException {
        assert !F.isEmpty(addrs);

        initFolder();

        try {
            for (InetSocketAddress addr : addrs) {
                File file = new File(folder, name(addr));

                file.createNewFile();
            }
        }
        catch (IOException e) {
            throw new GridSpiException("Failed to create file.", e);
        }
    }

    /** {@inheritDoc} */
    @Override public void unregisterAddresses(Collection<InetSocketAddress> addrs) throws GridSpiException {
        assert !F.isEmpty(addrs);

        initFolder();

        try {
            for (InetSocketAddress addr : addrs) {
                File file = new File(folder, name(addr));

                file.delete();
            }
        }
        catch (SecurityException e) {
            throw new GridSpiException("Failed to delete file.", e);
        }
    }

    /**
     * Creates file name for address.
     *
     * @param addr Node address.
     * @return Name.
     */
    private String name(InetSocketAddress addr) {
        assert addr != null;

        SB sb = new SB();

        sb.a(addr.getAddress().getHostAddress())
            .a(DELIM)
            .a(addr.getPort());

        return sb.toString();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridTcpDiscoverySharedFsIpFinder.class, this);
    }
}
