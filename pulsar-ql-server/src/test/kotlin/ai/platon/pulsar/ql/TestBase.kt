package ai.platon.pulsar.ql

import ai.platon.pulsar.common.DateTimeUtil
import ai.platon.pulsar.common.PulsarFiles
import ai.platon.pulsar.common.PulsarPaths
import ai.platon.pulsar.common.config.PulsarConstants.PULSAR_DEFAULT_TMP_DIR
import ai.platon.pulsar.common.sql.ResultSetFormatter
import org.h2.engine.SysProperties
import org.h2.store.fs.FileUtils
import org.h2.tools.DeleteDbFiles
import org.h2.tools.Server
import org.h2.tools.SimpleResultSet
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.nio.file.Files
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant

/**
 * The base class for all tests.
 */
abstract class TestBase {

    companion object {
        val history = mutableListOf<String>()
        val startTime = Instant.now()

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            history.add(0, "-- Time: $startTime")
            val sqls = history.joinToString("\n") { it }
            val ident = DateTimeUtil.now("MMdd.HH")
            val path = PulsarPaths.get(PULSAR_DEFAULT_TMP_DIR, "history", "sql-history-$ident.sql")
            Files.createDirectories(path.parent)
            PulsarFiles.saveTo(sqls, path, deleteIfExists = true)
        }
    }

    val productIndexUrl = "https://www.mia.com/formulas.html"
    val productDetailUrl = "https://www.mia.com/item-1687128.html"
    val newsIndexUrl = "http://news.baidu.com/guoji"
    val newsDetailUrl = "http://news.163.com/17/1119/09/D3JJF1290001875P.html"
    val newsDetailUrl2 = "http://www.chinanews.com/gn/2018/03-02/8458538.shtml"

    var urlGroups = mutableMapOf<String, Array<String>>()

    init {
        urlGroups["baidu"] = arrayOf(
                "https://www.baidu.com/s?wd=马航&oq=马航&ie=utf-8"
        )
        urlGroups["jd"] = arrayOf(
                "https://list.jd.com/list.html?cat=670,671,672",
                "https://item.jd.com/1238838350.html",
                "http://search.jd.com/Search?keyword=长城葡萄酒&enc=utf-8&wq=长城葡萄酒",
                "https://detail.tmall.com/item.htm?id=536690467392",
                "https://detail.tmall.com/item.htm?id=536690467392",
                "http://item.jd.com/1304924.html",
                "http://item.jd.com/3564062.html",
                "http://item.jd.com/1304923.html",
                "http://item.jd.com/3188580.html",
                "http://item.jd.com/1304915.html"
        )
        urlGroups["mia"] = arrayOf(
                "https://www.mia.com/formulas.html",
                "https://www.mia.com/item-2726793.html",
                "https://www.mia.com/item-1792382.html",
                "https://www.mia.com/item-1142813.html"
        )
        urlGroups["mogujie"] = arrayOf(
                "http://list.mogujie.com/book/jiadian/10059513",
                "http://list.mogujie.com/book/skirt",
                "http://shop.mogujie.com/detail/1kcnxeu",
                "http://shop.mogujie.com/detail/1lrjy2c"
        )
        urlGroups["meilishuo"] = arrayOf(
                "http://www.meilishuo.com/search/catalog/10057053",
                "http://www.meilishuo.com/search/catalog/10057051",
                "http://item.meilishuo.com/detail/1lvld0y",
                "http://item.meilishuo.com/detail/1lwebsw"
        )
        urlGroups["vip"] = arrayOf(
                "http://category.vip.com/search-1-0-1.html?q=3|29736",
                "https://category.vip.com/search-5-0-1.html?q=3|182725",
                "http://detail.vip.com/detail-2456214-437608397.html",
                "https://detail.vip.com/detail-2640560-476811105.html"
        )
        urlGroups["wikipedia"] = arrayOf(
                "https://en.wikipedia.org/wiki/URL",
                "https://en.wikipedia.org/wiki/URI",
                "https://en.wikipedia.org/wiki/URN"
        )
    }

    val db = ai.platon.pulsar.ql.h2.H2Db()
    val baseDir = db.baseTestDir
    var name: String = db.generateTempDbName()
    lateinit var conn: Connection
    lateinit var stat: Statement
    var server: Server? = null

    @Before
    open fun setup() {
        try {
            initializeDatabase()

            val numTests = this::class.members.count {
                it.annotations.any { it is org.junit.Test } && it.annotations.none { it is org.junit.Ignore }
            }
            if (numTests > 0) {
                history.add("\n-- Generated by ${javaClass.simpleName}")
            }

            conn = db.getConnection(name)
            stat = conn.createStatement()
        } catch (e: Throwable) {
            println(ai.platon.pulsar.common.StringUtil.stringifyException(e))
        }
    }

    @After
    open fun teardown() {
        try {
            destroyDatabase()
        } catch (e: Throwable) {
            println(ai.platon.pulsar.common.StringUtil.stringifyException(e))
        }
    }

    fun execute(sql: String, printResult: Boolean = true) {
        try {
            // TODO: different values are required in client and server side
            // this variable is shared between client and server in embed WebApp mode, but different values are required
            val lastSerializeJavaObject = SysProperties.serializeJavaObject
            SysProperties.serializeJavaObject = false

            val regex = "^(SELECT|CALL).+".toRegex()
            if (sql.filter { it != '\n' }.trimIndent().matches(regex)) {
                val rs = stat.executeQuery(sql)
                if (printResult) {
                    println(ResultSetFormatter(rs))
                }
            } else {
                val r = stat.execute(sql)
                if (printResult) {
                    println(r)
                }
            }
            SysProperties.serializeJavaObject = lastSerializeJavaObject

            history.add("${sql.trim { it.isWhitespace() }};")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun executeQuery(sql: String, printResult: Boolean = true): ResultSet {
        try {
            val rs = stat.executeQuery(sql)
            if (printResult) {
                println(ResultSetFormatter(rs))
            }
            history.add("${sql.trim { it.isWhitespace() }};")
            return rs
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        return SimpleResultSet()
    }

    /**
     * This method is called before a complete set of tests is run. It deletes
     * old database files in the test directory and trace files. It also starts
     * a TCP server if the test uses remote connections.
     */
    private fun initializeDatabase() {
        val config = db.config

        db.deleteDb(name)
        FileUtils.deleteRecursive(baseDir.toString(), true)
        DeleteDbFiles.execute(baseDir.toString(), null, true)
        if (config.networked) {
            val args = if (config.ssl)
                arrayOf("-tcpSSL", "-tcpPort", config.port.toString())
            else
                arrayOf("-tcpPort", config.port.toString())

            server = Server.createTcpServer(*args)
            try {
                server?.start()
                server?.let { println(it.status) }
            } catch (e: SQLException) {
                // println("FAIL: can not start server (may already be running)")
                e.printStackTrace()
            }
        }
    }

    /**
     * Clean test environment
     */
    private fun destroyDatabase() {
        stat.close()
        conn.close()

        server?.stop()
        server?.let { println(it.status) }

        db.deleteDb(name)
        FileUtils.deleteRecursive(baseDir.toString(), true)
    }
}
