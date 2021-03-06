// Copyright (C) GridGain Systems Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.affinity.*;
import org.gridgain.grid.kernal.controllers.*;
import org.gridgain.grid.kernal.processors.affinity.*;
import org.gridgain.grid.kernal.controllers.license.*;
import org.gridgain.grid.kernal.processors.dataload.*;
import org.gridgain.grid.kernal.processors.rest.*;
import org.gridgain.grid.kernal.managers.*;
import org.gridgain.grid.kernal.managers.authentication.*;
import org.gridgain.grid.kernal.managers.checkpoint.*;
import org.gridgain.grid.kernal.managers.collision.*;
import org.gridgain.grid.kernal.managers.communication.*;
import org.gridgain.grid.kernal.managers.deployment.*;
import org.gridgain.grid.kernal.managers.discovery.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.kernal.managers.failover.*;
import org.gridgain.grid.kernal.managers.loadbalancer.*;
import org.gridgain.grid.kernal.managers.metrics.*;
import org.gridgain.grid.kernal.managers.securesession.*;
import org.gridgain.grid.kernal.managers.swapspace.*;
import org.gridgain.grid.kernal.managers.topology.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.closure.*;
import org.gridgain.grid.kernal.processors.email.*;
import org.gridgain.grid.kernal.processors.job.*;
import org.gridgain.grid.kernal.processors.jobmetrics.*;
import org.gridgain.grid.kernal.processors.port.*;
import org.gridgain.grid.kernal.processors.resource.*;
import org.gridgain.grid.kernal.processors.rich.*;
import org.gridgain.grid.kernal.processors.schedule.*;
import org.gridgain.grid.kernal.processors.segmentation.*;
import org.gridgain.grid.kernal.processors.session.*;
import org.gridgain.grid.kernal.processors.task.*;
import org.gridgain.grid.kernal.processors.timeout.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.typedef.*;
import org.gridgain.grid.typedef.internal.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.worker.*;
import org.jetbrains.annotations.*;
import org.springframework.context.*;

import javax.management.*;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.GridLifecycleEventType.*;
import static org.gridgain.grid.GridSystemProperties.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.kernal.GridKernalState.*;
import static org.gridgain.grid.kernal.GridNodeAttributes.*;

/**
 * GridGain kernal.
 * <p/>
 * See <a href="http://en.wikipedia.org/wiki/Kernal">http://en.wikipedia.org/wiki/Kernal</a> for information on the
 * misspelling.
 *
 * @author 2012 Copyright (C) GridGain Systems
 * @version 4.0.2c.12042012
 */
public class GridKernal extends GridProjectionAdapter implements Grid, GridKernalMBean, Externalizable {
    /** Ant-augmented version number. */
    private static final String VER = "4.0.2c";

    /** Ant-augmented build number. */
    private static final String BUILD = "12042012";

    /** Ant-augmented release date. */
    private static final String RELEASE_DATE = "12042012";

    /** Ant-augmented copyright blurb. */
    private static final String COPYRIGHT = "2012 Copyright (C) GridGain Systems";

    /** System line separator. */
    private static final String NL = System.getProperty("line.separator");

    /** Periodic version check delay. */
    private static final long PERIODIC_VER_CHECK_DELAY = 1000 * 60 * 60; // Every hour.

    /** Periodic version check delay. */
    private static final long PERIODIC_VER_CHECK_CONN_TIMEOUT = 10 * 1000; // 10 seconds.

    /** Periodic version check delay. */
    private static final long PERIODIC_LIC_CHECK_DELAY = 1000 * 60; // Every minute.

    /** */
    private static final ThreadLocal<String> stash = new ThreadLocal<String>();

    /** */
    private static final GridPredicate<GridRichNode>[] EMPTY_PN = new PN[] {};

    /** Shutdown delay in msec. when license violation detected. */
    private static final int SHUTDOWN_DELAY = 60 * 1000;

    /** */
    private GridConfiguration cfg;

    /** */
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    private GridLogger log;

    /** */
    private String gridName;

    /** */
    private ObjectName kernalMBean;

    /** */
    private ObjectName locNodeMBean;

    /** */
    private ObjectName pubExecSvcMBean;

    /** */
    private ObjectName sysExecSvcMBean;

    /** */
    private ObjectName p2PExecSvcMBean;

    /** Kernal start timestamp. */
    private long startTime = System.currentTimeMillis();

    /** Spring context, potentially {@code null}. */
    private ApplicationContext springCtx;

    /** */
    private Timer updateNtfTimer;

    /** */
    private Timer licTimer;

    /** */
    private Timer metricsLogTimer;

    /** Indicate error on grid stop. */
    private boolean errOnStop;

    /** Node local store. */
    private GridNodeLocal nodeLoc;

    /** Release date. */
    private Date relDate;

    /** Kernal gateway. */
    private final AtomicReference<GridKernalGateway> gw = new AtomicReference<GridKernalGateway>();

    /** */
    private final GridBreaker stopBrk = new GridBreaker();

    /**
     * No-arg constructor is required by externalization.
     */
    public GridKernal() {
        super(null);
    }

    /**
     * @param springCtx Optional Spring application context.
     */
    public GridKernal(@Nullable ApplicationContext springCtx) {
        super(null);

        this.springCtx = springCtx;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        U.writeString(out, ctx.gridName());
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        stash.set(U.readString(in));
    }

    /**
     * Reconstructs object on demarshalling.
     *
     * @return Reconstructed object.
     * @throws ObjectStreamException Thrown in case of demarshalling error.
     */
    protected Object readResolve() throws ObjectStreamException {
        try {
            return G.grid(stash.get());
        }
        catch (IllegalStateException e) {
            throw U.withCause(new InvalidObjectException(e.getMessage()), e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isEnterprise() {
        return U.isEnterprise();
    }

    /** {@inheritDoc} */
    @Override public String name() {
        return gridName;
    }

    /** {@inheritDoc} */
    @Override public String getCopyright() {
        return COPYRIGHT;
    }

    /** {@inheritDoc} */
    @Override public String getLicenseFilePath() {
        assert cfg != null;

        return cfg.getLicenseUrl();
    }

    /** {@inheritDoc} */
    @Override public long getStartTimestamp() {
        return startTime;
    }

    /** {@inheritDoc} */
    @Override public String getStartTimestampFormatted() {
        return DateFormat.getDateTimeInstance().format(new Date(startTime));
    }

    /** {@inheritDoc} */
    @Override public long getUpTime() {
        return System.currentTimeMillis() - startTime;
    }

    /** {@inheritDoc} */
    @Override public String getUpTimeFormatted() {
        return X.timeSpan2HMSM(System.currentTimeMillis() - startTime);
    }

    /** {@inheritDoc} */
    @Override public String getFullVersion() {
        return VER + '-' + BUILD;
    }

    /** {@inheritDoc} */
    @Override public String getCheckpointSpiFormatted() {
        assert cfg != null;

        return Arrays.toString(cfg.getCheckpointSpi());
    }

    /** {@inheritDoc} */
    @Override public String getSwapSpaceSpiFormatted() {
        assert cfg != null;

        return Arrays.toString(cfg.getSwapSpaceSpi());
    }

    /** {@inheritDoc} */
    @Override public String getCommunicationSpiFormatted() {
        assert cfg != null;

        return cfg.getCommunicationSpi().toString();
    }

    /** {@inheritDoc} */
    @Override public String getDeploymentSpiFormatted() {
        assert cfg != null;

        return cfg.getDeploymentSpi().toString();
    }

    /** {@inheritDoc} */
    @Override public String getDiscoverySpiFormatted() {
        assert cfg != null;

        return cfg.getDiscoverySpi().toString();
    }

    /** {@inheritDoc} */
    @Override public String getEventStorageSpiFormatted() {
        assert cfg != null;

        return cfg.getEventStorageSpi().toString();
    }

    /** {@inheritDoc} */
    @Override public String getCollisionSpiFormatted() {
        assert cfg != null;

        return cfg.getCollisionSpi().toString();
    }

    /** {@inheritDoc} */
    @Override public String getFailoverSpiFormatted() {
        assert cfg != null;

        return Arrays.toString(cfg.getFailoverSpi());
    }

    /** {@inheritDoc} */
    @Override public String getLoadBalancingSpiFormatted() {
        assert cfg != null;

        return Arrays.toString(cfg.getLoadBalancingSpi());
    }

    /** {@inheritDoc} */
    @Override public String getMetricsSpiFormatted() {
        assert cfg != null;

        return cfg.getMetricsSpi().toString();
    }

    /** {@inheritDoc} */
    @Override public String getAuthenticationSpiFormatted() {
        assert cfg != null;

        return cfg.getAuthenticationSpi().toString();
    }

    /** {@inheritDoc} */
    @Override public String getSecureSessionSpiFormatted() {
        assert cfg != null;

        return cfg.getSecureSessionSpi().toString();
    }

    /** {@inheritDoc} */
    @Override public String getTopologySpiFormatted() {
        assert cfg != null;

        return Arrays.toString(cfg.getTopologySpi());
    }

    /** {@inheritDoc} */
    @Override public String getOsInformation() {
        return U.osString();
    }

    /** {@inheritDoc} */
    @Override public String getJdkInformation() {
        return U.jdkString();
    }

    /** {@inheritDoc} */
    @Override public String getOsUser() {
        return System.getProperty("user.name");
    }

    /** {@inheritDoc} */
    @Override public String getVmName() {
        return ManagementFactory.getRuntimeMXBean().getName();
    }

    /** {@inheritDoc} */
    @Override public String getInstanceName() {
        return gridName;
    }

    /** {@inheritDoc} */
    @Override public String getExecutorServiceFormatted() {
        assert cfg != null;

        return cfg.getExecutorService().toString();
    }

    /** {@inheritDoc} */
    @Override public String getGridGainHome() {
        assert cfg != null;

        return cfg.getGridGainHome();
    }

    /** {@inheritDoc} */
    @Override public String getGridLoggerFormatted() {
        assert cfg != null;

        return cfg.getGridLogger().toString();
    }

    /** {@inheritDoc} */
    @Override public String getMBeanServerFormatted() {
        assert cfg != null;

        return cfg.getMBeanServer().toString();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    @Override public UUID getLocalNodeId() {
        assert cfg != null;

        return cfg.getNodeId();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public Collection<String> getUserAttributesFormatted() {
        assert cfg != null;

        // That's why Java sucks...
        return F.transform(cfg.getUserAttributes().entrySet(), new C1<Map.Entry<String, ?>, String>() {
            @Override public String apply(Map.Entry<String, ?> e) {
                return e.getKey() + ", " + e.getValue().toString();
            }
        });
    }

    /** {@inheritDoc} */
    @Override public boolean isPeerClassLoadingEnabled() {
        assert cfg != null;

        return cfg.isPeerClassLoadingEnabled();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public Collection<String> getLifecycleBeansFormatted() {
        GridLifecycleBean[] beans = cfg.getLifecycleBeans();

        return F.isEmpty(beans) ? Collections.<String>emptyList() : F.transform(beans, F.<GridLifecycleBean>string());
    }

    /**
     * @param spiCls SPI class.
     * @return Spi version.
     * @throws GridException Thrown if {@link GridSpiInfo} annotation cannot be found.
     */
    private Serializable getSpiVersion(Class<? extends GridSpi> spiCls) throws GridException {
        assert spiCls != null;

        GridSpiInfo ann = U.getAnnotation(spiCls, GridSpiInfo.class);

        if (ann == null)
            throw new GridException("SPI implementation does not have annotation: " + GridSpiInfo.class);

        return ann.version();
    }

    /**
     * @param attrs Current attributes.
     * @param name  New attribute name.
     * @param val New attribute value.
     * @throws GridException If duplicated SPI name found.
     */
    private void add(Map<String, Object> attrs, String name, @Nullable Serializable val) throws GridException {
        assert attrs != null;
        assert name != null;

        if (attrs.put(name, val) != null) {
            if (name.endsWith(ATTR_SPI_CLASS))
                // User defined duplicated names for the different SPIs.
                throw new GridException("Failed to set SPI attribute. Duplicated SPI name found: " +
                    name.substring(0, name.length() - ATTR_SPI_CLASS.length()));

            // Otherwise it's a mistake of setting up duplicated attribute.
            assert false : "Duplicate attribute: " + name;
        }
    }

    /**
     * Notifies life-cycle beans of grid event.
     *
     * @param evt Grid event.
     * @throws GridException If user threw exception during start.
     */
    @SuppressWarnings({"CatchGenericClass"})
    private void notifyLifecycleBeans(GridLifecycleEventType evt) throws GridException {
        if (cfg.getLifecycleBeans() != null)
            for (GridLifecycleBean bean : cfg.getLifecycleBeans())
                if (bean != null)
                    bean.onLifecycleEvent(evt);
    }

    /**
     * Notifies life-cycle beans of grid event.
     *
     * @param evt Grid event.
     */
    @SuppressWarnings({"CatchGenericClass"})
    private void notifyLifecycleBeansEx(GridLifecycleEventType evt) {
        try {
            notifyLifecycleBeans(evt);
        }
        // Catch generic throwable to secure against user assertions.
        catch (Throwable e) {
            U.error(log, "Failed to notify lifecycle bean (safely ignored) [evt=" + evt +
                ", gridName=" + gridName + ']', e);
        }
    }

    /**
     * @param cfg Grid configuration to use.
     * @param errHnd Error handler to use for notification about startup problems.
     * @throws GridException Thrown in case of any errors.
     */
    @SuppressWarnings({"CatchGenericClass", "deprecation"})
    public void start(final GridConfiguration cfg, GridAbsClosure errHnd) throws GridException {
        gw.compareAndSet(null, new GridKernalGatewayImpl(cfg.getGridName()));

        GridKernalGateway gw = this.gw.get();

        gw.writeLock();

        try {
            switch (gw.getState()) {
                case STARTED: {
                    U.warn(log, "Grid has already been started (ignored).");

                    return;
                }

                case STARTING: {
                    U.warn(log, "Grid is already in process of being started (ignored).");

                    return;
                }

                case STOPPING: {
                    throw new GridException("Grid is in process of being stopped");
                }

                case STOPPED: {
                    break;
                }
            }

            gw.setState(STARTING);
        }
        finally {
            gw.writeUnlock();
        }

        try {
            relDate = new SimpleDateFormat("ddMMyyyy").parse(RELEASE_DATE);
        }
        catch (ParseException e) {
            throw new GridException("Failed to parse release date: " + RELEASE_DATE, e);
        }

        assert cfg != null;

        // Make sure we got proper configuration.
        A.notNull(cfg.getNodeId(), "cfg.getNodeId()");

        A.notNull(cfg.getMBeanServer(), "cfg.getMBeanServer()");
        A.notNull(cfg.getGridLogger(), "cfg.getGridLogger()");
        A.notNull(cfg.getMarshaller(), "cfg.getMarshaller()");
        A.notNull(cfg.getExecutorService(), "cfg.getExecutorService()");
        A.notNull(cfg.getUserAttributes(), "cfg.getUserAttributes()");

        // All SPIs should be non-null.
        A.notNull(cfg.getSwapSpaceSpi(), "cfg.getSwapSpaceSpi()");
        A.notNull(cfg.getCheckpointSpi(), "cfg.getCheckpointSpi()");
        A.notNull(cfg.getCommunicationSpi(), "cfg.getCommunicationSpi()");
        A.notNull(cfg.getDeploymentSpi(), "cfg.getDeploymentSpi()");
        A.notNull(cfg.getDiscoverySpi(), "cfg.getDiscoverySpi()");
        A.notNull(cfg.getEventStorageSpi(), "cfg.getEventStorageSpi()");
        A.notNull(cfg.getMetricsSpi(), "cfg.getMetricsSpi()");
        A.notNull(cfg.getAuthenticationSpi(), "cfg.getAuthenticationSpi()");
        A.notNull(cfg.getSecureSessionSpi(), "cfg.getSecureSessionSpi()");
        A.notNull(cfg.getCollisionSpi(), "cfg.getCollisionSpi()");
        A.notNull(cfg.getFailoverSpi(), "cfg.getFailoverSpi()");
        A.notNull(cfg.getLoadBalancingSpi(), "cfg.getLoadBalancingSpi()");
        A.notNull(cfg.getTopologySpi(), "cfg.getTopologySpi()");

        gridName = cfg.getGridName();

        this.cfg = cfg;

        log = cfg.getGridLogger().getLogger(getClass().getName() + (gridName != null ? '%' + gridName : ""));

        RuntimeMXBean rtBean = ManagementFactory.getRuntimeMXBean();

        // Ack various information.
        ackAsciiLogo();
        ackEdition();
        ackDaemon();
        ackLanguageRuntime();
        ackRemoteManagement();
        ackVmArguments(rtBean);
        ackClassPaths(rtBean);
        ackSystemProperties();
        ackEnvironmentVariables();
        ackSmtpConfiguration();
        ackCacheConfiguration();
        ackP2pConfiguration();

        // Run background network diagnostics.
        GridDiagnostic.runBackgroundCheck(gridName, cfg.getExecutorService(), log);

        GridUpdateNotifier verChecker = null;

        boolean notifyEnabled = !isDaemon() && !"false".equalsIgnoreCase(X.getSystemOrEnv(GG_UPDATE_NOTIFIER));

        if (notifyEnabled) {
            verChecker = new GridUpdateNotifier(gridName, false, 0);

            verChecker.checkForNewVersion(cfg.getExecutorService(), log);
        }

        // Ack 3-rd party licenses location.
        if (log.isInfoEnabled() && cfg.getGridGainHome() != null)
            log.info("3-rd party licenses can be found at: " + cfg.getGridGainHome() + File.separatorChar + "libs" +
                File.separatorChar + "licenses");

        // Check that user attributes are not conflicting
        // with internally reserved names.
        for (String name : cfg.getUserAttributes().keySet())
            if (name.startsWith(ATTR_PREFIX))
                throw new GridException("User attribute has illegal name: '" + name + "'. Note that all names " +
                    "starting with '" + ATTR_PREFIX + "' are reserved for internal use.");

        // Ack local node user attributes.
        logNodeUserAttributes();

        // Ack configuration.
        ackSpis();

        Map<String, Object> attrs = createNodeAttributes(cfg);

        // Spin out SPIs & managers.
        try {
            GridKernalContextImpl ctx = new GridKernalContextImpl(this, cfg, gw);

            nodeLoc = new GridNodeLocalImpl(ctx);

            // Set context into rich adapter.
            setKernalContext(ctx);

            // Start and configure resource processor first as it contains resources used
            // by all other managers and processors.
            GridResourceProcessor rsrcProc = new GridResourceProcessor(ctx);

            rsrcProc.setSpringContext(springCtx);

            startProcessor(ctx, rsrcProc);

            // Inject resources into lifecycle beans.
            if (cfg.getLifecycleBeans() != null)
                for (GridLifecycleBean bean : cfg.getLifecycleBeans())
                    if (bean != null)
                        rsrcProc.inject(bean);

            // Lifecycle notification.
            notifyLifecycleBeans(BEFORE_GRID_START);

            // Closure processor should be started before all others
            // (except for resource processor), as many components can depend on it.
            startProcessor(ctx, new GridClosureProcessor(ctx));

            // Start some other processors (order & place is important).
            startProcessor(ctx, new GridEmailProcessor(ctx));
            startProcessor(ctx, new GridPortProcessor(ctx));
            startProcessor(ctx, new GridRichProcessor(ctx));
            startProcessor(ctx, new GridJobMetricsProcessor(ctx));

            // Timeout processor needs to be started before managers,
            // as managers may depend on it.
            startProcessor(ctx, new GridTimeoutProcessor(ctx));

            // Start SPI managers.
            // NOTE: that order matters as there are dependencies between managers.
            startManager(ctx, new GridLocalMetricsManager(ctx), attrs);
            startManager(ctx, new GridAuthenticationManager(ctx), attrs);
            startManager(ctx, new GridSecureSessionManager(ctx), attrs);
            startManager(ctx, new GridIoManager(ctx), attrs);
            startManager(ctx, new GridCheckpointManager(ctx), attrs);

            startManager(ctx, new GridEventStorageManager(ctx), attrs);
            startManager(ctx, new GridDeploymentManager(ctx), attrs);
            startManager(ctx, new GridLoadBalancerManager(ctx), attrs);
            startManager(ctx, new GridFailoverManager(ctx), attrs);
            startManager(ctx, new GridCollisionManager(ctx), attrs);
            startManager(ctx, new GridTopologyManager(ctx), attrs);
            startManager(ctx, new GridSwapSpaceManager(ctx), attrs);

            ackSecurity(ctx);

            // Create the controllers. Order is important.
            startController(ctx, GridLicenseController.class);

            // Start processors before discovery manager, so they will
            // be able to start receiving messages once discovery completes.
            startProcessor(ctx, new GridAffinityProcessor(ctx));
            startProcessor(ctx, new GridSegmentationProcessor(ctx));
            startProcessor(ctx, new GridCacheProcessor(ctx));
            startProcessor(ctx, new GridTaskSessionProcessor(ctx));
            startProcessor(ctx, new GridJobProcessor(ctx));
            startProcessor(ctx, new GridTaskProcessor(ctx));
            startProcessor(ctx, new GridScheduleProcessor(ctx));
            startProcessor(ctx, new GridRestProcessor(ctx));
            startProcessor(ctx, new GridDataLoaderProcessor(ctx));

            gw.writeLock();

            try {
                gw.setState(STARTED);

                // Start discovery manager last to make sure that grid is fully initialized.
                startManager(ctx, new GridDiscoveryManager(ctx), attrs);
            }
            finally {
                gw.writeUnlock();
            }

            // Notify discovery manager the first to make sure that topology is discovered.
            ctx.discovery().onKernalStart();

            // Notify IO manager the second so further components can send and receive messages.
            ctx.io().onKernalStart();

            // Callbacks.
            for (GridComponent comp : ctx) {
                // Skip discovery manager.
                if (comp instanceof GridDiscoveryManager)
                    continue;

                // Skip IO manager.
                if (comp instanceof GridIoManager)
                    continue;

                comp.onKernalStart();
            }

            // Ack the license.
            ctx.license().ackLicense();

            // Register MBeans.
            registerKernalMBean();
            registerLocalNodeMBean();
            registerExecutorMBeans();

            // Lifecycle bean notifications.
            notifyLifecycleBeans(GridLifecycleEventType.AFTER_GRID_START);
        }
        catch (Throwable e) {
            U.error(log, "Got exception while starting. Will rollback startup routine.", e);

            errHnd.apply();

            stop(true, false);

            throw new GridException(e);
        }

        // Mark start timestamp.
        startTime = System.currentTimeMillis();

        // Ack latest version information.
        if (verChecker != null)
            verChecker.reportStatus(log);

        if (notifyEnabled) {
            updateNtfTimer = new Timer("gridgain-update-notifier-timer");

            // Setup periodic version check.
            updateNtfTimer.scheduleAtFixedRate(new GridTimerTask() {
                @Override public void safeRun() throws InterruptedException {
                    GridUpdateNotifier ntf = new GridUpdateNotifier(gridName, true, nodes(EMPTY_PN).size());

                    ntf.checkForNewVersion(cfg.getExecutorService(), log);

                    // Just wait for 10 secs.
                    Thread.sleep(PERIODIC_VER_CHECK_CONN_TIMEOUT);

                    // Report status if one is available.
                    // No-op is status is NOT available.
                    ntf.reportStatus(log);
                }
            }, PERIODIC_VER_CHECK_DELAY, PERIODIC_VER_CHECK_DELAY);
        }

        licTimer = new Timer("gridgain-license-checker");

        // Setup periodic license check.
        licTimer.scheduleAtFixedRate(new GridTimerTask() {
                @Override public void safeRun() throws InterruptedException {
                    try {
                        ctx.license().checkLicense();
                    }
                    // This exception only happens when license controller was unable
                    // to resolve license violation on its own and this grid instance
                    // now needs to be shutdown.
                    //
                    // Note that in most production configurations the license will
                    // have certain grace period and license controller will attempt
                    // to reload the license during the grace period.
                    //
                    // This exception thrown here means that grace period, if any,
                    // has expired and license violation is still unresolved.
                    catch (GridLicenseException ignored) {
                        U.error(log, "License violation is unresolved. GridGain node will shutdown in " +
                            (SHUTDOWN_DELAY / 1000) + " sec.");
                        U.error(log, "  ^-- Contact your support for immediate assistance (!)");

                        // Allow interruption to break from here since
                        // node is stopping anyways.
                        Thread.sleep(SHUTDOWN_DELAY);

                        G.stop(gridName, true);
                    }
                    // Safety net.
                    catch (Throwable e) {
                        U.error(log, "Unable to check the license due to system error.", e);
                        U.error(log, "Grid instance will be stopped...");

                        // Stop the grid if we get unknown license-related error.
                        // Should never happen. Practically an assertion...
                        G.stop(gridName, true);
                    }
                }
            }, PERIODIC_LIC_CHECK_DELAY, PERIODIC_LIC_CHECK_DELAY);

        long metricsLogFreq = cfg.getMetricsLogFrequency();

        if (metricsLogFreq > 0) {
            metricsLogTimer = new Timer("gridgain-metrics-logger");

            metricsLogTimer.scheduleAtFixedRate(new GridTimerTask() {
                private final DecimalFormat dblFmt = new DecimalFormat("#.##");

                @Override protected void safeRun() {
                    if (log.isInfoEnabled()) {
                        GridNodeMetrics m = localNode().metrics();

                        double cpuLoadPct = m.getCurrentCpuLoad() * 100;
                        double avgCpuLoadPct = m.getAverageCpuLoad() * 100;

                        long heapUsed = m.getHeapMemoryUsed();
                        long heapMax = m.getHeapMemoryMaximum();

                        double heapUsedPct = heapUsed * 100.0 / heapMax;

                        SB sb = new SB();

                        sb.a("Metrics [").
                            a("curCpuLoad=").a(dblFmt.format(cpuLoadPct)).a("%").
                            a(", avgCpuLoad=").a(dblFmt.format(avgCpuLoadPct)).a("%").
                            a(", heapUsed=").a(dblFmt.format(heapUsedPct)).a("%").
                            a("]");

                        log.info(sb.toString());
                    }
                }
            }, metricsLogFreq, metricsLogFreq);
        }

        if (log.isQuiet()) {
            U.quiet("System info:");
            U.quiet("    JVM: " + U.jvmVendor() + ", " + U.jreName() + " ver. " + U.jreVersion());
            U.quiet("    OS: " + U.osString() + ", " + System.getProperty("user.name"));
            U.quiet("    VM name: " + rtBean.getName());

            SB sb = new SB();

            for (GridPortRecord rec : ctx.ports().records())
                sb.a(rec.protocol()).a(":").a(rec.port()).a(" ");

            U.quiet("Local ports used [" + sb.toString().trim() + ']');

            GridNode locNode = localNode();

            U.quiet("GridGain started OK", "  ^-- [" +
                "grid=" + (gridName == null ? "default" : gridName) +
                ", nodeId8=" + U.id8(locNode.id()) +
                ", order=" + locNode.order() +
                ", CPUs=" + locNode.metrics().getTotalCpus() +
                ", addrs=" + getAddresses(locNode) +
                ']');

            U.quiet(U.rainbow("ZZZzz zz z..."));
        }
        else if (log.isInfoEnabled()) {
            String ack = "GridGain ver. " + VER + '-' + BUILD;

            String dash = U.dash(ack.length());

            SB sb = new SB();

            for (GridPortRecord rec : ctx.ports().records())
                sb.a(rec.protocol()).a(":").a(rec.port()).a(" ");

            String str =
                NL + NL +
                    ">>> " + dash + NL +
                    ">>> " + ack + NL +
                    ">>> " + dash + NL +
                    ">>> OS name: " + U.osString() + NL +
                    ">>> OS user: " + System.getProperty("user.name") + NL +
                    ">>> CPU(s): " + localNode().metrics().getTotalCpus() + NL +
                    ">>> VM information: " + U.jdkString() + NL +
                    ">>> VM name: " + rtBean.getName() + NL +
                    ">>> Grid name: " + gridName + NL +
                    ">>> Local node [" +
                        "ID=" + localNode().id().toString().toUpperCase() +
                        ", order=" + localNode().order() +
                    "]" + NL +
                    ">>> Local node addresses: " + getAddresses(localNode()) + NL +
                    ">>> Local ports: " + sb + NL;

            str += ">>> GridGain documentation: http://www.gridgain.com/product.html" + NL;

            log.info(str);
        }

        // Send node start email notification, if enabled.
        if (isSmtpEnabled() && isAdminEmailsSet() && cfg.isLifeCycleEmailNotification()) {
            SB sb = new SB();

            for (GridPortRecord rec : ctx.ports().records())
                sb.a(rec.protocol()).a(":").a(rec.port()).a(" ");

            String nid = localNode().id().toString().toUpperCase();
            String nid8 = localNode().id8().toUpperCase();

            GridEnterpriseLicense lic = ctx.license().license();

            String body =
                "GridGain node started with the following parameters:" + NL +
                NL +
                "----" + NL +
                "GridGain ver. " + VER + '-' + BUILD + NL +
                "Grid name: " + gridName + NL +
                "Node ID: " + nid + NL +
                "Node order: " + localNode().order() + NL +
                "Node addresses: " + getAddresses(localNode()) + NL +
                "Local ports: " + sb + NL +
                "OS name: " + U.osString() + NL +
                "OS user: " + System.getProperty("user.name") + NL +
                "CPU(s): " + localNode().metrics().getTotalCpus() + NL +
                "JVM name: " + U.jvmName() + NL +
                "JVM vendor: " + U.jvmVendor() + NL +
                "JVM version: " + U.jvmVersion() + NL +
                "VM name: " + rtBean.getName() + NL +
                "License ID: "  +  lic.id().toString().toUpperCase() + NL +
                "Licensed to: " + lic.userOrganization() + NL +
                "----" + NL +
                NL +
                "NOTE:" + NL +
                "This message is sent automatically to all configured admin emails." + NL +
                "To change this behavior use 'lifeCycleEmailNotify' grid configuration property." +
                NL + NL +
                "| www.gridgain.com" + NL +
                "| support@gridgain.com" + NL;

            sendAdminEmailAsync("GridGain node started: " + nid8, body, false);
        }
    }

    /**
     * @param node Grid node to get addresses for.
     * @return String containing internal and external addresses.
     */
    private String getAddresses(GridNode node) {
        Collection<String> addrs = new HashSet<String>();

        addrs.addAll(node.internalAddresses());
        addrs.addAll(node.externalAddresses());

        return addrs.toString();
    }

    /**
     * Creates attributes map and fills it in.
     *
     * @param cfg Grid configuration.
     * @return Map of all node attributes.
     * @throws GridException thrown if was unable to set up attribute.
     */
    @SuppressWarnings({"SuspiciousMethodCalls", "unchecked"})
    private Map<String, Object> createNodeAttributes(GridConfiguration cfg) throws GridException {
        Map<String, Object> attrs = new HashMap<String, Object>();

        final String[] includeProps = cfg.getIncludeProperties();

        if (includeProps == null || includeProps.length > 0) {
            try {
                // Stick all environment settings into node attributes.
                attrs.putAll(F.view(System.getenv(), new P1<String>() {
                    @Override public boolean apply(String name) {
                        return includeProps == null || U.containsStringArray(includeProps, name, true);
                    }
                }));

                if (log.isDebugEnabled())
                    log.debug("Added environment properties to node attributes.");
            }
            catch (SecurityException e) {
                throw new GridException("Failed to add environment properties to node attributes due to " +
                    "security violation: " + e.getMessage());
            }

            try {
                // Stick all system properties into node's attributes overwriting any
                // identical names from environment properties.
                for (Map.Entry<Object, Object> e : F.view(System.getProperties(), new P1<Object>() {
                    @Override public boolean apply(Object o) {
                        String name = (String)o;

                        return includeProps == null || U.containsStringArray(includeProps, name, true);
                    }
                }).entrySet()) {
                    Object val = attrs.get(e.getKey());

                    if (val != null && !val.equals(e.getValue()))
                        U.warn(log, "System property will override environment variable with the same name: "
                            + e.getKey());

                    attrs.put((String)e.getKey(), e.getValue());
                }

                if (log.isDebugEnabled())
                    log.debug("Added system properties to node attributes.");
            }
            catch (SecurityException e) {
                throw new GridException("Failed to add system properties to node attributes due to security " +
                    "violation: " + e.getMessage());
            }
        }

        // Add local network IPs and MACs.
        String ips = U.allLocalIps(); // Exclude loopbacks.
        String macs = U.allLocalMACs(); // Only enabled network interfaces.

        // Ack network context.
        if (log.isInfoEnabled()) {
            log.info("Non-loopback local IPs: " + (ips == null ? "N/A" : ips));
            log.info("Enabled local MACs: " + (macs == null ? "N/A" : macs));
        }

        // Warn about loopback.
        if (ips == null && macs == null)
            U.warn(log, "GridGain is starting on loopback address... Only nodes on the same physical " +
                "computer can participate in topology.",
                "GridGain is starting on loopback address...");

        // Stick in network context into attributes.
        add(attrs, ATTR_IPS, (ips == null ? "" : ips));
        add(attrs, ATTR_MACS, (macs == null ? "" : macs));

        // Stick in some system level attributes
        add(attrs, ATTR_JIT_NAME, U.getCompilerMx() == null ? "" : U.getCompilerMx().getName());
        add(attrs, ATTR_BUILD_VER, getFullVersion());
        add(attrs, ATTR_USER_NAME, System.getProperty("user.name"));
        add(attrs, ATTR_GRID_NAME, gridName);

        add(attrs, ATTR_DEPLOYMENT_MODE, cfg.getDeploymentMode());
        add(attrs, ATTR_LANG_RUNTIME, getLanguage());

        // Check daemon system property and override configuration if it's set.
        if (isDaemon())
            add(attrs, ATTR_DAEMON, "true");

        // Check edition and stick in edition attribute.
        if (isEnterprise())
            add(attrs, ATTR_ENT_EDITION, "true");

        // In case of the parsing error, JMX remote disabled or port not being set
        // node attribute won't be set.
        if (isJmxRemoteEnabled()) {
            String portStr = System.getProperty("com.sun.management.jmxremote.port");

            if (portStr != null)
                try {
                    add(attrs, ATTR_JMX_PORT, Integer.parseInt(portStr));
                }
                catch (NumberFormatException ignore) {
                    // No-op.
                }
        }

        // Whether restart is enabled and stick the attribute.
        add(attrs, ATTR_RESTART_ENABLED, Boolean.toString(isRestartEnabled()));

        // Stick in rest tcp and jetty ports.
        add(attrs, ATTR_REST_TCP_PORT, cfg.getRestTcpPort());
        add(attrs, ATTR_REST_JETTY_PORT, cfg.getRestJettyPort());

        // Stick in SPI versions and classes attributes.
        addAttributes(attrs, cfg.getCollisionSpi());
        addAttributes(attrs, cfg.getSwapSpaceSpi());
        addAttributes(attrs, cfg.getTopologySpi());
        addAttributes(attrs, cfg.getDiscoverySpi());
        addAttributes(attrs, cfg.getFailoverSpi());
        addAttributes(attrs, cfg.getCommunicationSpi());
        addAttributes(attrs, cfg.getEventStorageSpi());
        addAttributes(attrs, cfg.getCheckpointSpi());
        addAttributes(attrs, cfg.getLoadBalancingSpi());
        addAttributes(attrs, cfg.getMetricsSpi());
        addAttributes(attrs, cfg.getAuthenticationSpi());
        addAttributes(attrs, cfg.getSecureSessionSpi());
        addAttributes(attrs, cfg.getDeploymentSpi());

        // Set cache attribute.
        GridCacheAttributes[] cacheAttrVals = new GridCacheAttributes[cfg.getCacheConfiguration().length];

        int i = 0;

        for (GridCacheConfiguration cacheCfg : cfg.getCacheConfiguration()) {
            GridCacheAffinity aff = cacheCfg.getAffinity();

            cacheAttrVals[i++] = new GridCacheAttributes(
                cacheCfg.getName(),
                cacheCfg.getCacheMode() != null ? cacheCfg.getCacheMode() : GridCacheConfiguration.DFLT_CACHE_MODE,
                cacheCfg.getCacheMode() == PARTITIONED && cacheCfg.isNearEnabled(),
                cacheCfg.getPreloadMode(),
                aff != null ? aff.getClass().getCanonicalName() : null);
        }

        attrs.put(ATTR_CACHE, cacheAttrVals);

        // Set user attributes for this node.
        if (cfg.getUserAttributes() != null)
            for (Map.Entry<String, ?> e : cfg.getUserAttributes().entrySet()) {
                if (attrs.containsKey(e.getKey()))
                    U.warn(log, "User or internal attribute has the same name as environment or system " +
                        "property and will take precedence: " + e.getKey());

                attrs.put(e.getKey(), e.getValue());
            }

        return attrs;
    }

    /**
     * Add SPI version and class attributes into node attributes.
     *
     * @param attrs Node attributes map to add SPI attributes to.
     * @param spiList Collection of SPIs to get attributes from.
     * @throws GridException Thrown if was unable to set up attribute.
     */
    private void addAttributes(Map<String, Object> attrs, GridSpi... spiList) throws GridException {
        for (GridSpi spi : spiList) {
            Class<? extends GridSpi> spiCls = spi.getClass();

            add(attrs, U.spiAttribute(spi, ATTR_SPI_CLASS), spiCls.getName());
            add(attrs, U.spiAttribute(spi, ATTR_SPI_VER), getSpiVersion(spiCls));
        }
    }

    /** @throws GridException If registration failed. */
    private void registerKernalMBean() throws GridException {
        try {
            kernalMBean = U.registerMBean(
                cfg.getMBeanServer(),
                cfg.getGridName(),
                "Kernal",
                getClass().getSimpleName(),
                this,
                GridKernalMBean.class);

            if (log.isDebugEnabled())
                log.debug("Registered kernal MBean: " + kernalMBean);
        }
        catch (JMException e) {
            kernalMBean = null;

            throw new GridException("Failed to register kernal MBean.", e);
        }
    }

    /** @throws GridException If registration failed. */
    private void registerLocalNodeMBean() throws GridException {
        GridNodeMetricsMBean mbean = new GridLocalNodeMetrics(ctx.discovery().localNode());

        try {
            locNodeMBean = U.registerMBean(
                cfg.getMBeanServer(),
                cfg.getGridName(),
                "Kernal",
                mbean.getClass().getSimpleName(),
                mbean,
                GridNodeMetricsMBean.class);

            if (log.isDebugEnabled())
                log.debug("Registered local node MBean: " + locNodeMBean);
        }
        catch (JMException e) {
            locNodeMBean = null;

            throw new GridException("Failed to register local node MBean.", e);
        }
    }

    /** @throws GridException If registration failed. */
    private void registerExecutorMBeans() throws GridException {
        pubExecSvcMBean = registerExecutorMBean(cfg.getExecutorService(), "GridExecutionExecutor");
        sysExecSvcMBean = registerExecutorMBean(cfg.getSystemExecutorService(), "GridSystemExecutor");
        p2PExecSvcMBean = registerExecutorMBean(cfg.getPeerClassLoadingExecutorService(), "GridClassLoadingExecutor");
    }

    /**
     * @param exec Executor service to register.
     * @param name Property name for executor.
     * @return Name for created MBean.
     * @throws GridException If registration failed.
     */
    private ObjectName registerExecutorMBean(ExecutorService exec, String name) throws GridException {
        try {
            ObjectName res = U.registerMBean(
                cfg.getMBeanServer(),
                cfg.getGridName(),
                "Thread Pools",
                name,
                new GridExecutorServiceMBeanAdapter(exec),
                GridExecutorServiceMBean.class);

            if (log.isDebugEnabled())
                log.debug("Registered executor service MBean: " + res);

            return res;
        }
        catch (JMException e) {
            throw new GridException("Failed to register executor service MBean [name=" + name + ", exec=" + exec + ']',
                e);
        }
    }

    /**
     * Unregisters given mbean.
     *
     * @param mbean MBean to unregister.
     * @return {@code True} if successfully unregistered, {@code false} otherwise.
     */
    private boolean unregisterMBean(@Nullable ObjectName mbean) {
        if (mbean != null)
            try {
                cfg.getMBeanServer().unregisterMBean(mbean);

                if (log.isDebugEnabled())
                    log.debug("Unregistered MBean: " + mbean);

                return true;
            }
            catch (JMException e) {
                U.error(log, "Failed to unregister MBean.", e);

                return false;
            }

        return true;
    }

    /**
     * @param ctx Kernal context.
     * @param mgr Manager to start.
     * @param attrs SPI attributes to set.
     * @throws GridException Throw in case of any errors.
     */
    private void startManager(GridKernalContextImpl ctx, GridManager mgr, Map<String, Object> attrs)
        throws GridException {
        mgr.addSpiAttributes(attrs);

        // Set all node attributes into discovery manager,
        // so they can be distributed to all nodes.
        if (mgr instanceof GridDiscoveryManager)
            ((GridDiscoveryManager)mgr).setNodeAttributes(attrs);

        // Add manager to registry before it starts to avoid
        // cases when manager is started but registry does not
        // have it yet.
        ctx.add(mgr);

        try {
            mgr.start();
        }
        catch (GridException e) {
            throw new GridException("Failed to start manager: " + mgr, e);
        }
    }

    /**
     * @param ctx Kernal context.
     * @param proc Processor to start.
     * @throws GridException Thrown in case of any error.
     */
    private void startProcessor(GridKernalContextImpl ctx, GridComponent proc) throws GridException {
        ctx.add(proc);

        try {
            proc.start();
        }
        catch (GridException e) {
            throw new GridException("Failed to start processor: " + proc, e);
        }
    }

    /**
     * Creates controller for given interface. Implementation should be in "impl" sub-package
     * and class name should have "Impl" postfix.
     *
     * @param ctx Context.
     * @param itf Controller interface.
     * @param <T> Type of the controller interface.
     * @return Controller instance or no-op dynamic proxy for this interface.
     * @throws GridException Thrown if controller fails to initialize.
     * @throws IllegalArgumentException Thrown if input class is not a valid controller
     *      interface.
     */
    @SuppressWarnings("unchecked")
    private <T extends GridController> T startController(GridKernalContextImpl ctx, Class<T> itf) throws GridException {
        assert itf != null;

        Package pkg = itf.getPackage();

        if (pkg == null)
            throw new GridException("Internal error (package object was not found) for: " + itf);

        Class<?> cls = null;

        GridController ctrl = null;

        try {
            cls = Class.forName(pkg.getName() + ".impl." + itf.getSimpleName() + "Impl");
        }
        catch (ClassNotFoundException ignore) {
            ctrl = (GridController)Proxy.newProxyInstance(itf.getClassLoader(), new Class[] {itf},
                new InvocationHandler() {
                    @Nullable
                    @Override public Object invoke(Object proxy, Method mtd, Object[] args) throws Throwable {
                        return "implemented".equals(mtd.getName()) && args == null ?  Boolean.FALSE : null;
                    }
                }
            );
        }

        if (cls != null) {
            if (!itf.isAssignableFrom(cls))
                throw new IllegalArgumentException("Type does not represent valid controller: " + itf);

            try {
                ctrl = (GridController)cls.getConstructor(new Class[] { GridKernalContext.class }).newInstance(ctx);

                ctrl.start();
            }
            catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to instantiate controller for: " + itf, e);
            }
            catch (InstantiationException e) {
                throw new IllegalArgumentException("Failed to instantiate controller for: " + itf, e);
            }
            catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("Failed to instantiate controller for: " + itf, e);
            }
            catch (InvocationTargetException e) {
                throw new IllegalArgumentException("Failed to instantiate controller for: " + itf, e);
            }
        }

        assert ctrl != null;

        if (log.isDebugEnabled())
            log.debug("Controller started [itf=" + itf.getSimpleName() + ", proxy=" + !ctrl.implemented() + ']');

        ctx.add(ctrl);

        return (T)ctrl;
    }

    /**
     * Gets "on" or "off" string for given boolean value.
     *
     * @param b Boolean value to convert.
     * @return Result string.
     */
    private String onOff(boolean b) {
        return b ? "on" : "off";
    }

    /**
     *
     * @return Whether or not REST is enabled.
     */
    private boolean isRestEnabled() {
        assert cfg != null;

        return cfg.isRestEnabled();
    }

    /**
     * Acks remote management.
     */
    private void ackRemoteManagement() {
        SB sb = new SB();

        sb.a("Remote Management [");

        boolean on = isJmxRemoteEnabled();

        sb.a("restart: ").a(onOff(isRestartEnabled())).a(", ");
        sb.a("REST: ").a(onOff(isRestEnabled())).a(", ");
        sb.a("JMX (");
        sb.a("remote: ").a(onOff(on));

        if (on) {
            sb.a(", ");

            sb.a("port: ").a(System.getProperty("com.sun.management.jmxremote.port", "<n/a>")).a(", ");
            sb.a("auth: ").a(onOff(Boolean.getBoolean("com.sun.management.jmxremote.authenticate"))).a(", ");

            // By default SSL is enabled, that's why additional check for null is needed.
            // See http://docs.oracle.com/javase/6/docs/technotes/guides/management/agent.html
            sb.a("ssl: ").a(onOff(Boolean.getBoolean("com.sun.management.jmxremote.ssl") ||
                System.getProperty("com.sun.management.jmxremote.ssl") == null));
        }

        sb.a(")");

        sb.a(']');

        U.log(log, sb);
    }

    /**
     * Acks GridGain edition.
     */
    private void ackEdition() {
        assert log != null;

        U.log(log, "<< " + (isEnterprise() ? "Enterprise" : "Community") + " Edition >>");
    }

    /**
     * Acks ASCII-logo. Thanks to http://patorjk.com/software/taag
     */
    private void ackAsciiLogo() {
        assert log != null;

        if (System.getProperty(GG_NO_ASCII) == null) {
            String tag = "---==++ REAL TIME BIG DATA ++==---";
            String ver = "ver. " + VER + '-' + BUILD;

            // Big thanks to: http://patorjk.com/software/taag
            // Font name "Small Slant"
            if (log.isQuiet())
                U.quiet(
                    "  _____     _     _______      _         ",
                    " / ___/____(_)___/ / ___/___ _(_)___     ",
                    "/ (_ // __/ // _  / (_ // _ `/ // _ \\   ",
                    "\\___//_/ /_/ \\_,_/\\___/ \\_,_/_//_//_/",
                    " ",
                    " " + U.rainbow(tag),
                    " " + U.pad((tag.length() - ver.length()) / 2) + ver,
                    COPYRIGHT,
                    "",
                    "Quiet mode.",
                    "  ^-- To disable add -DGRIDGAIN_QUIET=false or \"-v\" to ggstart.{sh|bat}"
                );
            else if (log.isInfoEnabled())
                log.info(NL + NL +
                    ">>>   _____     _     _______      _         " + NL +
                    ">>>  / ___/____(_)___/ / ___/___ _(_)___     " + NL +
                    ">>> / (_ // __/ // _  / (_ // _ `/ // _ \\   " + NL +
                    ">>> \\___//_/ /_/ \\_,_/\\___/ \\_,_/_//_//_/" + NL +
                    ">>> " + NL +
                    ">>>  " + U.rainbow(tag) + NL +
                    ">>> " + U.pad((tag.length() - ver.length()) / 2) + ver + NL +
                    ">>> " + COPYRIGHT + NL
                );
        }
    }

    /**
     * Logs out language runtime.
     */
    private void ackLanguageRuntime() {
        U.log(log, "Language runtime: " + getLanguage());
    }

    /**
     * @return Language runtime.
     */
    @SuppressWarnings("ThrowableInstanceNeverThrown")
    private String getLanguage() {
        boolean scala = false;
        boolean groovy = false;
        boolean clojure = false;

        for (StackTraceElement elem : Thread.currentThread().getStackTrace()) {
            String s = elem.getClassName().toLowerCase();

            if (s.contains("scala")) {
                scala = true;

                break;
            }
            else if (s.contains("groovy")) {
                groovy = true;

                break;
            }
            else if (s.contains("clojure")) {
                clojure = true;

                break;
            }
        }

        if (scala) {
            Properties props = new Properties();

            try {
                props.load(getClass().getResourceAsStream("/library.properties"));

                return "Scala ver. " + props.getProperty("version.number", "<unknown>");
            }
            catch (Throwable ignore) {
                return "Scala ver. <unknown>";
            }
        }

        // How to get Groovy and Clojure version at runtime?!?
        return groovy ? "Groovy" : clojure ? "Clojure" : U.jdkName() + " ver. " + U.jdkVersion();
    }

    /**
     * Stops grid instance.
     *
     * @param cancel Whether or not to cancel running jobs.
     * @param wait If {@code true} then method will wait for all task being executed until they finish their
     *      execution.
     */
    public void stop(boolean cancel, boolean wait) {
        String nid = getLocalNodeId().toString().toUpperCase();
        String nid8 = U.id8(getLocalNodeId()).toUpperCase();

        gw.compareAndSet(null, new GridKernalGatewayImpl(gridName));

        boolean firstStop = false;

        GridKernalGateway gw = this.gw.get();

        synchronized (stopBrk) {
            gw.writeLock();

            try {
                switch (gw.getState()) {
                    case STARTED: {
                        if (stopBrk.isOn()) {
                            firstStop = true;

                            stopBrk.trip();
                        }

                        break;
                    }

                    case STARTING: {
                        U.warn(log, "Attempt to stop starting grid. This operation " +
                            "cannot be guaranteed to be successful.");

                        break;
                    }

                    case STOPPING: {
                        if (log.isDebugEnabled())
                            log.debug("Grid is being stopped by another thread. Aborting this stop sequence " +
                                "allowing other thread to finish[]");

                        return;
                    }

                    case STOPPED: {
                        if (log.isDebugEnabled())
                            log.debug("Grid is already stopped. Nothing to do[]");

                        return;
                    }
                }
            }
            finally {
                gw.writeUnlock();
            }
        }

        // Notify lifecycle beans in case when this thread is first to
        // stop the kernal. Notify outside of the lock.
        if (firstStop) {
            if (log.isDebugEnabled())
                log.debug("Notifying lifecycle beans.");

            notifyLifecycleBeansEx(GridLifecycleEventType.BEFORE_GRID_STOP);
        }

        List<GridComponent> comps = ctx.components();

        // Callback component in reverse order while kernal is still functional
        // if called in the same thread, at least.
        for (ListIterator<GridComponent> it = comps.listIterator(comps.size()); it.hasPrevious();) {
            GridComponent comp = it.previous();

            try {
                comp.onKernalStop(cancel, wait);
            }
            catch (Throwable e) {
                errOnStop = true;

                U.error(log, "Failed to pre-stop processor: " + comp, e);
            }
        }

        gw.writeLock();

        try {
            assert gw.getState() == STARTED || gw.getState() == STARTING;

            // No more kernal calls from this point on.
            gw.setState(STOPPING);

            // Cancel update notification timer.
            if (updateNtfTimer != null)
                updateNtfTimer.cancel();

            // Cancel license timer.
            if (licTimer != null)
                licTimer.cancel();

            // Cancel metrics log timer.
            if (metricsLogTimer != null)
                metricsLogTimer.cancel();

            // Clear node local store.
            nodeLoc.clear();

            if (log.isDebugEnabled())
                log.debug("Grid " + (gridName == null ? "" : '\'' + gridName + "' ") + "is stopping.");
        }
        finally {
            gw.writeUnlock();
        }

        // Unregister MBeans.
        if (!(
            unregisterMBean(pubExecSvcMBean) &
                unregisterMBean(sysExecSvcMBean) &
                unregisterMBean(p2PExecSvcMBean) &
                unregisterMBean(kernalMBean) &
                unregisterMBean(locNodeMBean)))
            errOnStop = false;

        // Stop components in reverse order.
        for (ListIterator<GridComponent> it = comps.listIterator(comps.size()); it.hasPrevious();) {
            GridComponent comp = it.previous();

            try {
                comp.stop(cancel, wait);

                if (log.isDebugEnabled())
                    log.debug("Component stopped: " + comp);
            }
            catch (Throwable e) {
                errOnStop = true;

                U.error(log, "Failed to stop component (ignoring): " + comp, e);
            }
        }

        // Lifecycle notification.
        notifyLifecycleBeansEx(GridLifecycleEventType.AFTER_GRID_STOP);

        for (GridWorker w : GridWorkerGroup.instance(gridName).activeWorkers()) {
            String n1 = w.gridName() == null ? "" : w.gridName();
            String n2 = gridName == null ? "" : gridName;

            /*
             * We should never get a runnable from one grid instance
             * in the runnable group for another grid instance.
             */
            assert n1.equals(n2) : "Different grid names: [n1=" + n1 + ", n2=" + n2 + "]";

            if (log.isDebugEnabled())
                log.debug("Joining on runnable after grid has stopped: " + w);

            try {
                w.join();
            }
            catch (InterruptedException e) {
                errOnStop = true;

                U.error(log, "Got interrupted during grid stop (ignoring).", e);

                break;
            }
        }

        // Release memory.
        GridWorkerGroup.removeInstance(gridName);

        gw.writeLock();

        try {
            gw.setState(STOPPED);
        }
        finally {
            gw.writeUnlock();
        }

        // Ack stop.
        if (log.isQuiet()) {
            if (!errOnStop)
                U.quiet("GridGain stopped OK [uptime=" +
                    X.timeSpan2HMSM(System.currentTimeMillis() - startTime) + ']');
            else
                U.quiet("GridGain stopped wih ERRORS [uptime=" +
                    X.timeSpan2HMSM(System.currentTimeMillis() - startTime) + ']');
        }
        else if (log.isInfoEnabled())
            if (!errOnStop) {
                String ack = "GridGain ver. " + VER + '-' + BUILD + " stopped OK";

                String dash = U.dash(ack.length());

                log.info(NL + NL +
                    ">>> " + dash + NL +
                    ">>> " + ack + NL +
                    ">>> " + dash + NL +
                    ">>> Grid name: " + gridName + NL +
                    ">>> Grid uptime: " + X.timeSpan2HMSM(System.currentTimeMillis() - startTime) +
                    NL +
                    NL);
            }
            else {
                String ack = "GridGain ver. " + VER + '-' + BUILD + " stopped with ERRORS";

                String dash = U.dash(ack.length());

                log.info(NL + NL +
                    ">>> " + ack + NL +
                    ">>> " + dash + NL +
                    ">>> Grid name: " + gridName + NL +
                    ">>> Grid uptime: " + X.timeSpan2HMSM(System.currentTimeMillis() - startTime) +
                    NL +
                    ">>> See log above for detailed error message." + NL +
                    ">>> Note that some errors during stop can prevent grid from" + NL +
                    ">>> maintaining correct topology since this node may have" + NL +
                    ">>> not exited grid properly." + NL +
                    NL);
            }

        // Print out all the stop watches.
        W.printAll();

        // Send node start email notification, if enabled.
        if (isSmtpEnabled() && isAdminEmailsSet() && cfg.isLifeCycleEmailNotification()) {
            String errOk = errOnStop ? "with ERRORS" : "OK";

            String headline = "GridGain ver. " + VER + '-' + BUILD + " stopped " + errOk + ":";
            String subj = "GridGain node stopped " + errOk + ": " + nid8;

            GridEnterpriseLicense lic = ctx.license().license();

            String body =
                headline + NL +
                NL +
                "----" + NL +
                "GridGain ver. " + VER + '-' + BUILD + NL +
                "Grid name: " + gridName + NL +
                "Node ID: " + nid + NL +
                "Node uptime: " + X.timeSpan2HMSM(System.currentTimeMillis() - startTime) + NL +
                "License ID: "  +  lic.id().toString().toUpperCase() + NL +
                "Licensed to: " + lic.userOrganization() + NL +
                "----" + NL +
                NL +
                "NOTE:" + NL +
                "This message is sent automatically to all configured admin emails." + NL +
                "To change this behavior use 'lifeCycleEmailNotify' grid configuration property.";

            if (errOnStop)
                body +=
                    NL + NL +
                    "NOTE:" + NL +
                    "See node's log for detailed error message." + NL +
                    "Some errors during stop can prevent grid from" + NL +
                    "maintaining correct topology since this node may " + NL +
                    "have not exited grid properly.";

            body +=
                NL + NL +
                "| www.gridgain.com" + NL +
                "| support@gridgain.com" + NL;

            // We can't use email processor at this point.
            // So we use "raw" method of sending.
            try {
                U.sendEmail(
                    // Static SMTP configuration data.
                    cfg.getSmtpHost(),
                    cfg.getSmtpPort(),
                    cfg.isSmtpSsl(),
                    cfg.isSmtpStartTls(),
                    cfg.getSmtpUsername(),
                    cfg.getSmtpPassword(),
                    cfg.getSmtpFromEmail(),

                    // Per-email data.
                    subj,
                    body,
                    false,
                    Arrays.asList(cfg.getAdminEmails())
                );
            }
            catch (GridException e) {
                U.error(log, "Failed to send lifecycle email notification.", e);
            }
        }
    }

    /**
     * Returns {@code true} if grid successfully stop.
     *
     * @return true if grid successfully stop.
     */
    @Deprecated
    public boolean isStopSuccess() {
        return !errOnStop;
    }

    /**
     * USED ONLY FOR TESTING.
     *
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Internal cache instance.
     */
    /*@java.test.only*/
    public <K, V> GridCacheAdapter<K, V> internalCache() {
        return internalCache(null);
    }

    /**
     * USED ONLY FOR TESTING.
     *
     * @param name Cache name.
     * @param <K>  Key type.
     * @param <V>  Value type.
     * @return Internal cache instance.
     */
    /*@java.test.only*/
    public <K, V> GridCacheAdapter<K, V> internalCache(@Nullable String name) {
        return ctx.cache().internalCache(name);
    }

    /**
     * It's intended for use by internal marshalling implementation only.
     *
     * @return Kernal context.
     */
    public GridKernalContext context() {
        return ctx;
    }

    /**
     * Prints all system properties in debug mode.
     */
    private void ackSystemProperties() {
        assert log != null;

        if (log.isDebugEnabled())
            for (Object key : U.asIterable(System.getProperties().keys()))
                log.debug("System property [" + key + '=' + System.getProperty((String)key) + ']');
    }

    /**
     * Prints all user attributes in info mode.
     */
    private void logNodeUserAttributes() {
        assert log != null;

        if (log.isInfoEnabled())
            for (Map.Entry<?, ?> attr : cfg.getUserAttributes().entrySet())
                log.info("Local node user attribute [" + attr.getKey() + '=' + attr.getValue() + ']');
    }

    /**
     * Prints all environment variables in debug mode.
     */
    private void ackEnvironmentVariables() {
        assert log != null;

        if (log.isDebugEnabled())
            for (Map.Entry<?, ?> envVar : System.getenv().entrySet())
                log.debug("Environment variable [" + envVar.getKey() + '=' + envVar.getValue() + ']');
    }

    /**
     * Acks daemon mode status.
     */
    private void ackDaemon() {
        U.log(log, "Daemon mode: " + (isDaemon() ? "on" : "off"));
    }

    /**
     *
     * @return {@code True} is this node is daemon.
     */
     private boolean isDaemon() {
        assert cfg != null;

        return cfg.isDaemon() || "true".equalsIgnoreCase(System.getProperty(GG_DAEMON));
     }

    /**
     * {@inheritDoc}
     */
    @Override public boolean isJmxRemoteEnabled() {
        return System.getProperty("com.sun.management.jmxremote") != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override public boolean isRestartEnabled() {
        return System.getProperty(GG_SUCCESS_FILE) != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override public boolean isSmtpEnabled() {
        assert cfg != null;

        return cfg.getSmtpHost() != null;
    }

    /**
     * Prints all configuration properties in info mode and SPIs in debug mode.
     */
    private void ackSpis() {
        assert log != null;

        if (log.isDebugEnabled()) {
            log.debug("+-------------+");
            log.debug("START SPI LIST:");
            log.debug("+-------------+");
            log.debug("Grid checkpoint SPI     : " + Arrays.toString(cfg.getCheckpointSpi()));
            log.debug("Grid collision SPI      : " + cfg.getCollisionSpi());
            log.debug("Grid communication SPI  : " + cfg.getCommunicationSpi());
            log.debug("Grid deployment SPI     : " + cfg.getDeploymentSpi());
            log.debug("Grid discovery SPI      : " + cfg.getDiscoverySpi());
            log.debug("Grid event storage SPI  : " + cfg.getEventStorageSpi());
            log.debug("Grid failover SPI       : " + Arrays.toString(cfg.getFailoverSpi()));
            log.debug("Grid load balancing SPI : " + Arrays.toString(cfg.getLoadBalancingSpi()));
            log.debug("Grid metrics SPI        : " + cfg.getMetricsSpi());
            log.debug("Grid authentication SPI : " + cfg.getAuthenticationSpi());
            log.debug("Grid secure session SPI : " + cfg.getSecureSessionSpi());
            log.debug("Grid swap space SPI     : " + Arrays.toString(cfg.getSwapSpaceSpi()));
            log.debug("Grid topology SPI       : " + Arrays.toString(cfg.getTopologySpi()));
        }
    }

    /**
     *
     */
    private void ackCacheConfiguration() {
        GridCacheConfiguration[] cacheCfgs = cfg.getCacheConfiguration();

        if (cacheCfgs == null || cacheCfgs.length == 0)
            U.warn(log, "Cache is not configured - data grid is off.");
        else {
            SB sb = new SB();

            for (GridCacheConfiguration c : cacheCfgs)
                sb.a("'").a(c.getName()).a("', ");

            String names = sb.toString();

            U.log(log, "Configured caches [" + names.substring(0, names.length() - 2) + ']');
        }
    }

    /**
     *
     */
    private void ackP2pConfiguration() {
        assert cfg != null;

        if (!cfg.isPeerClassLoadingEnabled())
            U.warn(
                log,
                "P2P class loading is disabled. Visor monitoring will not work. In most cases you should enabled " +
                "P2P class loading but limit the classes that are P2P loaded using 'peerClassLoadingClassPathExclude' " +
                "configuration property, if necessary.",
                "P2P class loading is disabled. Visor monitoring will not work."
            );
    }

    /**
     * Prints security status.
     *
     * @param ctx Kernal context.
     */
    private void ackSecurity(GridKernalContext ctx) {
        if (log.isQuiet())
            U.quiet("Security status [authentication=" + onOff(ctx.auth().securityEnabled()) + ", " +
                "secure-session=" + onOff(ctx.secureSession().securityEnabled()) + ']');
        else if (log.isInfoEnabled())
            log.info("Security status [authentication=" + onOff(ctx.auth().securityEnabled()) + ", " +
                "secure-session=" + onOff(ctx.secureSession().securityEnabled()) + ']');

        if (!ctx.isEnterprise())
            U.warn(log, "Security is disabled.");
    }

    /**
     * Prints out SMTP configuration.
     */
    private void ackSmtpConfiguration() {
        String host = cfg.getSmtpHost();

        boolean ssl = cfg.isSmtpSsl();
        int port = cfg.getSmtpPort();

        if (host != null) {
            String from = cfg.getSmtpFromEmail();

            if (log.isQuiet())
                U.quiet("SMTP enabled [host=" + host + ":" + port + ", ssl=" + (ssl ? "on" : "off") + ", from=" +
                    from + ']');
            else if (log.isInfoEnabled()) {
                String[] adminEmails = cfg.getAdminEmails();

                log.info("SMTP enabled [host=" + host + ", port=" + port + ", ssl=" + ssl + ", from=" +
                    from + ']');
                log.info("Admin emails: " + (!isAdminEmailsSet() ? "N/A" : Arrays.toString(adminEmails)));
            }

            if (!isAdminEmailsSet())
                U.warn(log, "Admin emails are not set - automatic email notifications are off.");
        }
        else
            U.warn(log, "SMTP is not configured - email notifications are off.");
    }

    /**
     * Tests whether or not admin emails are set.
     *
     * @return {@code True} if admin emails are set and not empty.
     */
    private boolean isAdminEmailsSet() {
        assert cfg != null;

        String[] a = cfg.getAdminEmails();

        return a != null && a.length > 0;
    }

    /**
     * Prints out VM arguments and GRIDGAIN_HOME in info mode.
     *
     * @param rtBean Java runtime bean.
     */
    private void ackVmArguments(RuntimeMXBean rtBean) {
        assert log != null;

        // Ack GRIDGAIN_HOME and VM arguments.
        if (log.isQuiet())
            U.quiet("GRIDGAIN_HOME=" + cfg.getGridGainHome());
        else if (log.isInfoEnabled()) {
            log.info("GRIDGAIN_HOME=" + cfg.getGridGainHome());
            log.info("VM arguments: " + rtBean.getInputArguments());
        }
    }

    /**
     * Prints out class paths in debug mode.
     *
     * @param rtBean Java runtime bean.
     */
    private void ackClassPaths(RuntimeMXBean rtBean) {
        assert log != null;

        // Ack all class paths.
        if (log.isDebugEnabled()) {
            log.debug("Boot class path: " + rtBean.getBootClassPath());
            log.debug("Class path: " + rtBean.getClassPath());
            log.debug("Library path: " + rtBean.getLibraryPath());
        }
    }

    /** {@inheritDoc} */
    @Override public GridConfiguration configuration() {
        return cfg;
    }

    /** {@inheritDoc} */
    @Override public GridLogger log() {
        guard();

        try {
            return log;
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridProjection projectionForNodes(Collection<? extends GridNode> nodes) {
        A.notNull(nodes, "nodes");

        guard();

        try {
            return new GridProjectionImpl(this, ctx, F.viewReadOnly(nodes, ctx.rich().richNode()));
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridProjection projectionForNodes(GridRichNode[] nodes) {
        return projectionForNodes(Arrays.asList(nodes));
    }

    /** {@inheritDoc} */
    @Override public GridEvent waitForEvent(long timeout, @Nullable Runnable c,
        @Nullable GridPredicate<? super GridEvent> p, int[] types) throws GridException {
        A.ensure(timeout >= 0, "timeout >= 0");

        guard();

        try {
            return ctx.event().waitForEvent(timeout, c, p, types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean removeCheckpoint(String key) {
        A.notNull(key, "key");

        guard();

        try {
            return ctx.checkpoint().removeCheckpoint(key);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridFuture<GridEvent> waitForEventAsync(@Nullable GridPredicate<? super GridEvent> p,
        int[] types) {
        guard();

        try {
            return ctx.event().waitForEvent(p, types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public String version() {
        return VER;
    }

    /** {@inheritDoc} */
    @Override public String build() {
        return BUILD;
    }

    /** {@inheritDoc} */
    @Override public Date releaseDate() {
        // Date object is mutable, so we have to return copy here.
        return new Date(relDate.getTime());
    }

    /** {@inheritDoc} */
    @Override public String copyright() {
        return COPYRIGHT;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridPredicate<GridRichNode> predicate() {
        guard();

        try {
            return F.alwaysTrue();
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean pingNode(String nodeId) {
        A.notNull(nodeId, "nodeId");

        return pingNode(UUID.fromString(nodeId));
    }

    /** {@inheritDoc} */
    @Override public long topologyHash(Iterable<? extends GridNode> nodes) {
        A.notNull(nodes, "nodes");

        guard();

        try {
            return ctx.discovery().topologyHash(nodes);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void undeployTaskFromGrid(String taskName) throws JMException {
        A.notNull(taskName, "taskName");

        try {
            undeployTask(taskName);
        }
        catch (GridException e) {
            throw U.jmException(e);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public String executeTask(String taskName, String arg) throws JMException {
        try {
            return this.<String, String>execute(taskName, arg, null).get();
        }
        catch (GridException e) {
            throw U.jmException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> sendAdminEmailAsync(String subj, String body, boolean html) {
        A.notNull(subj, "subj");
        A.notNull(body, "body");

        if (isSmtpEnabled() && isAdminEmailsSet()) {
            guard();

            try {
                return ctx.email().schedule(subj, body, html, Arrays.asList(cfg.getAdminEmails()));
            }
            finally {
                unguard();
            }
        }
        else
            return new GridFinishedFuture<Boolean>(ctx, false);
    }

    /** {@inheritDoc} */
    @Override public void sendAdminEmail(String subj, String body, boolean html) throws GridException {
        A.notNull(subj, "subj");
        A.notNull(body, "body");

        if (isSmtpEnabled() && isAdminEmailsSet()) {
            guard();

            try {
                ctx.email().sendNow(subj, body, html, Arrays.asList(cfg.getAdminEmails()));
            }
            finally {
                unguard();
            }
        }
    }

    /** {@inheritDoc} */
    @Override public GridEnterpriseLicense license() {
        guard();

        try {
            return ctx.license().license();
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void updateLicense(String lic) throws GridLicenseException {
        guard();

        try {
            ctx.license().updateLicense(lic);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void addLocalEventListener(GridLocalEventListener lsnr, int[] types) {
        A.notNull(lsnr, "lsnr");
        A.notNull(types, "types");

        guard();

        try {
            if (types.length == 0)
                throw new GridRuntimeException("Array of event types cannot be empty.");

            ctx.event().addLocalEventListener(lsnr, types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void addLocalEventListener(GridLocalEventListener lsnr, int type, @Nullable int... types) {
        A.notNull(lsnr, "lsnr");

        guard();

        try {
            addLocalEventListener(lsnr, new int[] { type });

            if (types != null && types.length > 0)
                addLocalEventListener(lsnr, types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean pingNodeByAddress(String host) {
        guard();

        try {
            for (GridRichNode n : nodes(EMPTY_PN))
                if (n.externalAddresses().contains(host) || n.internalAddresses().contains(host))
                    return n.ping();

            return false;
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean removeLocalEventListener(GridLocalEventListener lsnr, int[] types) {
        A.notNull(lsnr, "lsnr");

        guard();

        try {
            return ctx.event().removeLocalEventListener(lsnr, types);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    @Override public void addMessageListener(GridMessageListener lsnr, GridPredicate<Object>[] p) {
        guard();

        try {
            ctx.io().addUserMessageListener(lsnr, p);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    @Override public boolean removeMessageListener(GridMessageListener lsnr) {
        guard();

        try {
            return ctx.io().removeUserMessageListener(lsnr);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridRichNode localNode() {
        guard();

        try {
            GridRichNode node = ctx.rich().rich(ctx.discovery().localNode());

            assert node != null;

            return node;
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> runLocal(Runnable r) {
        A.notNull(r, "r");

        guard();

        try {
            return ctx.closure().runLocalSafe(r, false);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public <R> GridFuture<R> callLocal(Callable<R> c) {
        A.notNull(c, "c");

        guard();

        try {
            return ctx.closure().callLocalSafe(c, false);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public <R> GridScheduleFuture<R> scheduleLocal(Callable<R> c, String ptrn) throws GridException {
        guard();

        try {
            return ctx.schedule().schedule(c, ptrn);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridScheduleFuture<?> scheduleLocal(Runnable c, String ptrn) throws GridException {
        guard();

        try {
            return ctx.schedule().schedule(c, ptrn);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public <K, V> GridNodeLocal<K, V> nodeLocal() {
        guard();

        try {
            return nodeLoc;
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean pingNode(UUID nodeId) {
        A.notNull(nodeId, "nodeId");

        guard();

        try {
            return ctx.discovery().pingNode(nodeId);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void deployTask(Class<? extends GridTask> taskCls) throws GridException {
        A.notNull(taskCls, "taskCls");

        deployTask(taskCls, U.detectClassLoader(taskCls));
    }

    /** {@inheritDoc} */
    @Override public void deployTask(Class<? extends GridTask> taskCls, ClassLoader clsLdr) throws GridException {
        A.notNull(taskCls, "taskCls", clsLdr, "clsLdr");

        guard();

        try {
            GridDeployment dep = ctx.deploy().deploy(taskCls, clsLdr);

            if (dep == null)
                throw new GridDeploymentException("Failed to deploy task (was task (re|un)deployed?): " + taskCls);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridProjection parent() {
        return null; // Kernal is the root projection - therefore it doesn't have parent.
    }

    /** {@inheritDoc} */
    @Override public Map<String, Class<? extends GridTask<?, ?>>>
    localTasks(@Nullable GridPredicate<? super Class<? extends GridTask<?, ?>>>[] p) {
        guard();

        try {
            return ctx.deploy().findAllTasks(p);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void undeployTask(String taskName) throws GridException {
        A.notNull(taskName, "taskName");

        guard();

        try {
            ctx.deploy().undeployTask(taskName);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public Collection<GridEvent> localEvents(GridPredicate<? super GridEvent>[] p) {
        guard();

        try {
            return ctx.event().localEvents(p);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void recordLocalEvent(GridEvent evt) {
        A.notNull(evt, "evt");

        guard();

        try {
            ctx.event().record(evt);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> GridCache<K, V> cache(@Nullable String name) {
        guard();

        try {
            return ctx.cache().cache(name);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> GridCache<K, V> cache() {
        guard();

        try {
            return ctx.cache().cache();
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public Collection<GridCache<?, ?>> caches(GridPredicate<? super GridCache<?, ?>>[] p) {
        guard();

        try {
            return F.retain(ctx.cache().caches(), true, p);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void writeToSwap(@Nullable String space, Object key, @Nullable Object val,
        @Nullable ClassLoader ldr) throws GridException {
        A.notNull(key, "key");

        guard();

        try {
            ctx.swap().write(space, key, val, ldr);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable @Override public <T> T readFromSwap(@Nullable String space, Object key, @Nullable ClassLoader ldr)
        throws GridException {
        A.notNull(key, "key");

        guard();

        try {
            return ctx.swap().<T>read(space, key, ldr);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void removeFromSwap(@Nullable String space, Object key, final GridInClosure<Object> c,
        @Nullable final ClassLoader ldr) throws GridException {
        A.notNull(key, "key");

        guard();

        try {
            GridInClosureX<byte[]> c1 = null;

            if (c != null)
                c1 = new CIX1<byte[]>() {
                    @Override public void applyx(byte[] rmv) throws GridException {
                        Object val = null;

                        ClassLoader ldr0 = ldr != null ? ldr : classLoader();

                        if (rmv != null)
                            val = U.unmarshal(cfg.getMarshaller(), new ByteArrayInputStream(rmv), ldr0);

                        c.apply(val);
                    }
                };

            ctx.swap().remove(space, key, c1, ldr);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public void clearSwapSpace(@Nullable String space) throws GridException {
        guard();

        try {
            ctx.swap().clear(space);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridProjection projectionForNodeIds(UUID[] nodeIds) {
        return projectionForNodeIds(Arrays.asList(nodeIds));
    }

    /** {@inheritDoc} */
    @Override public GridProjection projectionForNodeIds(Collection<UUID> nodeIds) {
        A.notNull(nodeIds, "nodeIds");

        guard();

        try {
            return new GridProjectionImpl(this, ctx, F.viewReadOnly(nodeIds, new C1<UUID, GridRichNode>() {
                @SuppressWarnings("unchecked")
                @Nullable @Override public GridRichNode apply(UUID nodeId) {
                    return node(nodeId, null);
                }
            }));
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public Collection<GridRichNode> nodes(GridPredicate<? super GridRichNode>[] p) {
        guard();

        try {
            return F.view(F.viewReadOnly(ctx.discovery().allNodes(), ctx.rich().richNode()), p);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public GridRichNode rich(GridNode node) {
        guard();

        try {
            GridRichNode n = ctx.rich().rich(node);

            assert n != null;

            return n;
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean dynamic() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public <K, V> GridDataLoader<K, V> dataLoader(@Nullable String cacheName) {
        guard();

        try {
            return ctx.dataLoad().dataLoader(cacheName);
        }
        finally {
            unguard();
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridKernal.class, this);
    }
}
