package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.Freezable
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.proxy.ProxyManagerFactory
import ai.platon.pulsar.crawl.fetch.FetchResult
import ai.platon.pulsar.crawl.fetch.FetchTask
import ai.platon.pulsar.crawl.protocol.ForwardingResponse
import ai.platon.pulsar.persist.RetryScope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * TODO: multiple context support
 * */
class PrivacyContextManager(
        val proxyManagerFactory: ProxyManagerFactory,
        val driverManager: WebDriverManager,
        val immutableConfig: ImmutableConfig
): Freezable() {
    companion object {
        private val globalActiveContext = AtomicReference<BrowserPrivacyContext>()
        val zombieContexts = ConcurrentLinkedQueue<BrowserPrivacyContext>()
    }

    val proxyManager = proxyManagerFactory.get()
    val maxRetry = 2
//    val retryingTasks = LinkedBlockingQueue<FetchTask>()

    val activeContext get() = getOrCreate()

    fun run(task: FetchTask, fetchFun: (FetchTask, ManagedWebDriver) -> FetchResult): FetchResult {
        return whenUnfrozen { run0(task, fetchFun) }
    }

    suspend fun submit(task: FetchTask, fetchFun: suspend (FetchTask, ManagedWebDriver) -> FetchResult): FetchResult {
        // TODO: have synchronization bug
        waitUntilFreezerChannelIsClosed()
        return submit0(task, fetchFun)
    }

    fun reset() {
        freeze {
            globalActiveContext.getAndSet(null)?.apply {
                zombieContexts.add(this)
                use { it.close() }
            }
        }
    }

    private fun run0(task: FetchTask, fetchFun: (FetchTask, ManagedWebDriver) -> FetchResult): FetchResult {
        var result: FetchResult

        var i = 1
        var retry: Boolean
        do {
            beforeRun(task)
            result = try {
                activeContext.run(task) { _, driver -> fetchFun(task, driver) }
            } catch (e: PrivacyLeakException) {
                FetchResult(task, ForwardingResponse.retry(task.page, RetryScope.PRIVACY))
            }
            retry = afterRun(task, result)
        } while (retry && i++ <= maxRetry)

        return result
    }

    private suspend fun submit0(task: FetchTask, fetchFun: suspend (FetchTask, ManagedWebDriver) -> FetchResult): FetchResult {
        var result: FetchResult

        var i = 1
        var retry: Boolean
        do {
            beforeRun(task)
            result = try {
                activeContext.submit(task) { _, driver -> fetchFun(task, driver) }
            } catch (e: PrivacyLeakException) {
                FetchResult(task, ForwardingResponse.retry(task.page, RetryScope.PRIVACY))
            }
            retry = afterRun(task, result)
        } while (retry && i++ <= maxRetry)

        return result
    }

    private fun beforeRun(task: FetchTask) {
        if (activeContext.isPrivacyLeaked) {
            // hung up all other tasks
            reset()
            task.reset()
        }
    }

    /**
     * Handle when after run
     * @return true if privacy is leaked
     * */
    private fun afterRun(task: FetchTask, result: FetchResult): Boolean {
        val status = result.response.status
        return when {
            status.isRetry(RetryScope.PRIVACY) -> activeContext.markWarning().let { true }
            status.isSuccess -> activeContext.markSuccess().let { false }
            else -> false
        }
    }

    private fun getOrCreate(): BrowserPrivacyContext {
        return whenUnfrozen {
            if (globalActiveContext.get() == null) {
                globalActiveContext.set(BrowserPrivacyContext(driverManager, proxyManager, immutableConfig))
            }
            globalActiveContext.get()
        }
    }
}
