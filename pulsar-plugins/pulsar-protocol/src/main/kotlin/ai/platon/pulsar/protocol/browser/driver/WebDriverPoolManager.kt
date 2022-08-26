package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.PreemptChannelSupport
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.metrics.AppMetrics
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.crawl.PulsarEventHandler
import ai.platon.pulsar.crawl.fetch.FetchTask
import ai.platon.pulsar.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.crawl.fetch.driver.WebDriverException
import ai.platon.pulsar.crawl.fetch.privacy.BrowserInstanceId
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolException
import com.codahale.metrics.Gauge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.Throws

class WebDriverTask<R> (
        val browserId: BrowserInstanceId,
        val page: WebPage,
        val priority: Int = 0,
        val runWith: suspend (driver: WebDriver) -> R
) {
    val volatileConfig get() = page.conf
    val eventHandler get() = volatileConfig.getBeanOrNull(PulsarEventHandler::class)
}

/**
 * Created by vincent on 18-1-1.
 * Copyright @ 2013-2017 Platon AI. All rights reserved
 */
open class WebDriverPoolManager(
        val driverFactory: WebDriverFactory,
        val immutableConfig: ImmutableConfig,
        val suppressMetrics: Boolean = false
): Parameterized, PreemptChannelSupport("WebDriverPoolManager"), AutoCloseable {
    companion object {
        val DRIVER_CLOSE_TIME_OUT = Duration.ofSeconds(60)
    }

    private val logger = LoggerFactory.getLogger(WebDriverPoolManager::class.java)
    private val closed = AtomicBoolean()
    private val isActive get() = !closed.get() && AppContext.isActive

    val driverSettings get() = driverFactory.driverSettings
    val idleTimeout = Duration.ofMinutes(18)

    val driverPools = ConcurrentSkipListMap<BrowserInstanceId, LoadingWebDriverPool>()
    val retiredPools = ConcurrentSkipListSet<BrowserInstanceId>()

    val startTime = Instant.now()
    var lastActiveTime = startTime
    val idleTime get() = Duration.between(lastActiveTime, Instant.now())
    val isIdle get() = idleTime > idleTimeout

    val numReset by lazy { AppMetrics.reg.meter(this, "numReset") }
    val numTimeout by lazy { AppMetrics.reg.meter(this, "numTimeout") }
    val gauges = mapOf(
            "waitingDrivers" to Gauge { numWaiting },
            "freeDrivers" to Gauge { numFreeDrivers },
            "workingDrivers" to Gauge { numWorkingDrivers },
            "onlineDrivers" to Gauge { numOnline },
            "pTasks" to Gauge { numPreemptiveTasks.get() },
            "runningPTasks" to Gauge { numRunningPreemptiveTasks.get() },
            "pendingNTasks" to Gauge { numPendingNormalTasks.get() },
            "runningNTasks" to Gauge { numRunningNormalTasks.get() },
            "idleTime" to Gauge { idleTime.readable() }
    ).takeUnless { suppressMetrics }

    val numWaiting get() = driverPools.values.sumOf { it.numWaiting.get() }
    val numFreeDrivers get() = driverPools.values.sumOf { it.numFree }
    val numWorkingDrivers get() = driverPools.values.sumOf { it.numWorking.get() }
    val numAvailableDrivers get() = driverPools.values.sumOf { it.numAvailable }
    val numOnline get() = driverPools.values.sumOf { it.onlineDrivers.size }

//    private val launchLock = ReentrantLock()
    private val launchMutex = Mutex()

    init {
        gauges?.let { AppMetrics.reg.registerAll(this, it) }
    }

//    @Throws(WebDriverException::class)
//    suspend fun <R> run(browseFun: suspend (driver: WebDriver) -> R?) =
//        run(FetchTask.create(WebPage.newTestWebPage()), browseFun)

    @Throws(WebDriverException::class)
    suspend fun <R> run(task: FetchTask, browseFun: suspend (driver: WebDriver) -> R?) =
        run(WebDriverTask(BrowserInstanceId.DEFAULT, task.page, task.priority, browseFun))

    /**
     * TODO: consider pro-actor model instead
     *
     * reactor: tell me if you can do this job
     * proactor: here is a job, tell me if you finished it
     *
     * @return The result of action, or null if timeout
     * */
    @Throws(WebDriverException::class)
    suspend fun <R> run(browserId: BrowserInstanceId, task: FetchTask,
                        browseFun: suspend (driver: WebDriver) -> R?
    ) = run(WebDriverTask(browserId, task.page, task.priority, browseFun))

    @Throws(WebDriverException::class)
    suspend fun <R> run(task: WebDriverTask<R>): R? {
        lastActiveTime = Instant.now()
        return run0(task).also { lastActiveTime = Instant.now() }
    }

    /**
     * Create a driver pool, but the driver pool is not added to [driverPools]
     * */
    fun createUnmanagedDriverPool(
            browserId: BrowserInstanceId = BrowserInstanceId.DEFAULT,
            priority: Int = 0,
            volatileConfig: VolatileConfig? = null
    ): LoadingWebDriverPool {
        return LoadingWebDriverPool(browserId, priority, driverFactory, immutableConfig)
    }

    fun isRetiredPool(browserId: BrowserInstanceId) = retiredPools.contains(browserId)

    /**
     * Cancel the fetch task specified by [url] remotely
     * NOTE: A cancel request should run immediately not waiting for any browser task return
     * */
    fun cancel(url: String): WebDriver? {
        var driver: WebDriver? = null
        driverPools.values.forEach { driverPool ->
            driver = driverPool.firstOrNull { it.navigateEntry.pageUrl == url }?.also {
                it.cancel()
            }
        }
        return driver
    }

    /**
     * Cancel the fetch task specified by [url] remotely
     * NOTE: A cancel request should run immediately not waiting for any browser task return
     * */
    fun cancel(browserId: BrowserInstanceId, url: String): WebDriver? {
        val driverPool = driverPools[browserId] ?: return null
        return driverPool.firstOrNull { it.navigateEntry.pageUrl == url }?.also { it.cancel() }
    }

    /**
     * Cancel all the fetch tasks, stop loading all pages
     * */
    fun cancelAll() {
        driverPools.values.forEach { driverPool ->
            driverPool.onlineDrivers.toList().parallelStream().forEach { it.cancel() }
        }
    }

    /**
     * Cancel all the fetch tasks, stop loading all pages
     * */
    fun cancelAll(browserId: BrowserInstanceId) {
        val driverPool = driverPools[browserId] ?: return
        driverPool.onlineDrivers.toList().parallelStream().forEach { it.cancel() }
    }

    /**
     * Cancel all running tasks and close all web drivers
     * */
    fun closeDriverPool(browserId: BrowserInstanceId, timeToWait: Duration) {
        numReset.mark()
        // Mark all drivers are canceled
        doCloseDriverPool(browserId)
    }

    fun formatStatus(browserId: BrowserInstanceId, verbose: Boolean = false): String {
        return driverPools[browserId]?.formatStatus(verbose)?:""
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            driverPools.keys.forEach { doCloseDriverPool(it) }
            driverPools.clear()
            logger.info("Web driver pool manager is closed")
            if (gauges?.entries?.isEmpty() == false || driverPools.isNotEmpty()) {
                val s = formatStatus(true)
                if (s.isNotEmpty()) {
                    logger.info(s)
                }
            }
            driverFactory.close()
        }
    }

    override fun toString(): String = formatStatus(false)

    @Throws(WebDriverException::class)
    private suspend fun <R> run0(task: WebDriverTask<R>): R? {
        val browserId = task.browserId
        var result: R? = null
        whenNormalDeferred {
            if (!isActive) {
                return@whenNormalDeferred null
            }

            if (isRetiredPool(browserId)) {
                throw WebDriverPoolException("Web driver pool is retired | $browserId")
            }

            val driverPool = computeDriverPoolIfAbsent(browserId, task)
            if (!driverPool.isActive) {
                throw WebDriverPoolException("Driver pool is already closed | $driverPool | $browserId")
            }

            var driver: WebDriver? = null
            try {
                // Mutual exclusion for coroutines.
                driver = launchMutex.withLock {
                    if (isActive) poll(driverPool, task) else return@whenNormalDeferred null
                }

                // do not take up too much time on this driver
                val fetchTaskTimeout = driverSettings.fetchTaskTimeout
                result = withTimeoutOrNull(fetchTaskTimeout.toMillis()) {
                    if (isActive) task.runWith(driver) else null
                }

                if (result == null) {
                    numTimeout.mark()
                    driverPool.numTimeout.incrementAndGet()
                    driverPool.numDismissWarnings.incrementAndGet()

                    // This should not happen since the task itself should handle the timeout event
                    logger.warn("Web driver task timeout({}) | {} | {}",
                        fetchTaskTimeout.readable(), formatStatus(browserId), browserId)
                } else {
                    driverPool.numSuccess.incrementAndGet()
                    driverPool.numDismissWarnings.decrementAndGet()
                }
            } finally {
                driver?.let { driverPool.put(it) }
            }
        }

        return result
    }

    private fun <R> poll(driverPool: LoadingWebDriverPool, task: WebDriverTask<R>): WebDriver {
        val notLaunched = driverPool.numTasks.compareAndSet(0, 1)
        return if (notLaunched) {
            launchAndPoll(driverPool, task)
        } else {
            pollWebDriver(driverPool, task)
        }
    }

    private fun <R> pollWebDriver(driverPool: LoadingWebDriverPool, task: WebDriverTask<R>): WebDriver {
        val timeout = driverSettings.pollingDriverTimeout
        val driver = driverPool.poll(task.priority, task.volatileConfig, timeout)
        driver.startWork()
        return driver
    }

    private fun <R> launchAndPoll(driverPool: LoadingWebDriverPool, task: WebDriverTask<R>): WebDriver {
        onBeforeBrowserLaunch(task.page)
        return pollWebDriver(driverPool, task).also { onAfterBrowserLaunch(task.page, it) }
    }

    private fun onBeforeBrowserLaunch(page: WebPage) {
        val eventHandler = page.conf.getBeanOrNull(PulsarEventHandler::class)
        try {
            eventHandler?.loadEventHandler?.onBeforeBrowserLaunch?.invoke(page)
        } catch (t: Throwable) {
            logger.warn(t.stringify("Unexpected exception before browser launch - "))
        }
    }

    private fun onAfterBrowserLaunch(page: WebPage, driver: WebDriver) {
        val eventHandler = page.conf.getBeanOrNull(PulsarEventHandler::class)

        try {
            eventHandler?.loadEventHandler?.onAfterBrowserLaunch?.invoke(page, driver)
        } catch (t: Throwable) {
            logger.warn(t.stringify("Unexpected exception after browser launch - "))
        }
    }

    @Synchronized
    private fun <R> computeDriverPoolIfAbsent(browserId: BrowserInstanceId, task: WebDriverTask<R>): LoadingWebDriverPool {
        return driverPools.computeIfAbsent(browserId) { createUnmanagedDriverPool(browserId, task.priority, task.volatileConfig) }
    }

    private fun doCloseDriverPool(browserId: BrowserInstanceId) {
        preempt {
            retiredPools.add(browserId)

            val isGUI = driverSettings.isGUI
            val displayMode = driverSettings.displayMode
            val driverPool = when {
                !isGUI -> driverPools.remove(browserId)
                isGUI && driverPools.size > 10 -> {
                    driverPools.values.filter { it.isRetired }.minByOrNull { it.lastActiveTime }
                }
                else -> null
            }

            if (driverPool != null) {
                driverPool.isRetired = true
                logger.info(driverPool.formatStatus(verbose = true))
                logger.info("Closing driver pool with {} mode | {}", displayMode, browserId)
                driverPool.close()
            } else {
                logger.info("Web drivers are in {} mode, please close it manually | {} ", displayMode, browserId)
            }

            driverFactory.browserInstanceManager.closeIfPresent(browserId)
        }
    }

    private fun formatStatus(verbose: Boolean = false): String {
        val sb = StringBuilder()
        if (driverPools.isNotEmpty()) {
            driverPools.entries.joinTo(sb, "\n") { it.value.formatStatus(verbose) + " | " + it.key }
        }
        return sb.toString()
    }
}
