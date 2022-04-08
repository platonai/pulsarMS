package ai.platon.pulsar.examples.sites.simuwang

import ai.platon.pulsar.crawl.DefaultPulsarEventHandler
import ai.platon.pulsar.crawl.event.CloseMaskLayerHandler
import ai.platon.pulsar.crawl.event.LoginHandler
import ai.platon.pulsar.ql.context.SQLContexts

open class SiMuCrawler {
    // general parameters
    val portalUrl = "https://dc.simuwang.com/"
    val args = "-i 30s -ii 30s -ol a[href~=product] -tl 10"
    // login parameters
    val loginUrl = portalUrl
    val activateSelector = "button.comp-login-b2"
    val usernameSelector = "input[name=username]"
    val username = System.getenv("EXOTIC_SIMUWANG_USERNAME")
    val passwordSelector = "input[type=password]"
    val password = System.getenv("EXOTIC_SIMUWANG_PASSWORD")
    val submitSelector = "button.comp-login-btn"
    // mask layer handling
    val closeMaskLayerSelector = ".comp-alert-btn"

    val context = SQLContexts.create()
    val session = context.createSession()
    val eventHandler = DefaultPulsarEventHandler().also {
        val loginHandler = LoginHandler(loginUrl,
            usernameSelector, username, passwordSelector, password, submitSelector, activateSelector)
        it.loadEventHandler.onAfterBrowserLaunch.addLast(loginHandler)

        val closeMaskLayerHandler = CloseMaskLayerHandler(closeMaskLayerSelector)
        it.simulateEventHandler.onAfterCheckDOMState.addLast(closeMaskLayerHandler)
    }
    val options = session.options(args, eventHandler)

    open fun crawl() {
        // load out pages
        val pages = session.loadOutPages(portalUrl, options)
        // parse to jsoup documents
        val documents = pages.map { session.parse(it) }
        // use the documents
        // ...
        // wait for all done
        context.await()
    }
}

fun main() = SiMuCrawler().crawl()
