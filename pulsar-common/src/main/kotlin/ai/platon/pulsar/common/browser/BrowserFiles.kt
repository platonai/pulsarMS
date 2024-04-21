package ai.platon.pulsar.common.browser

import ai.platon.pulsar.common.*
import com.google.common.collect.Iterators
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.RandomStringUtils
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.MonthDay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

internal class ContextGroup(val group: String) {
    
    class PathIterator(private val paths: Iterable<Path>): Iterator<Path> {
        private val iterator = Iterators.cycle(paths)
        
        override fun hasNext(): Boolean {
            return paths.iterator().hasNext()
        }
        
        override fun next(): Path {
            return iterator.next()
        }
    }
    
    private val paths = ConcurrentSkipListSet<Path>()
    
    val size: Int
        get() = paths.size
    
    val iterator = PathIterator(paths)
    
    fun add(path: Path) {
        paths.add(path)
    }
}

object BrowserFiles {
    
    private val logger = getLogger(this)
    
    // The prefix for all temporary privacy contexts. System context, prototype context and default context are not
    // required to start with the prefix.
    const val CONTEXT_DIR_PREFIX = "cx."
    
    const val PID_FILE_NAME = "launcher.pid"
    
    val TEMPORARY_UDD_EXPIRY = Duration.ofHours(12)

    private val contextGroups = ConcurrentHashMap<String, ContextGroup>()
    
    private val cleanedUserDataDirs = ConcurrentSkipListSet<Path>()
    
    @Throws(IOException::class)
    @Synchronized
    fun computeTestContextDir() {
        return runWithFileLock { computeNextSequentialContextDir0("test", 5) }
    }

    @Throws(IOException::class)
    @Synchronized
    fun computeNextSequentialContextDir(group: String = "default", maxContexts: Int = 10): Path {
        return runWithFileLock { computeNextSequentialContextDir0(group, maxContexts) }
    }
    
    @Throws(IOException::class)
    @Synchronized
    fun computeRandomContextDir(ident: String = ""): Path {
        return runWithFileLock { computeRandomContextDir0(ident) }
    }

    @Throws(IOException::class)
    fun cleanUpContextTmpDir(expiry: Duration) {
        val hasSiblingPidFile: (Path) -> Boolean = { path -> Files.exists(path.resolveSibling(PID_FILE_NAME)) }
        Files.walk(AppPaths.CONTEXT_TMP_DIR, 3)
            .filter { it !in cleanedUserDataDirs }
            .filter { it.isDirectory() && hasSiblingPidFile(it) }.forEach { path ->
                deleteTemporaryUserDataDirWithLock(path, expiry)
            }
    }
    
    @Throws(IOException::class)
    fun deleteTemporaryUserDataDirWithLock(userDataDir: Path, expiry: Duration) {
        runWithFileLock { deleteTemporaryUserDataDir0(userDataDir, expiry) }
    }
    
    @Throws(IOException::class)
    @Synchronized
    private fun <T> runWithFileLock(supplier: () -> T): T {
        val lockFile = AppPaths.BROWSER_TMP_DIR_LOCK
        // Opens or creates a file, returning a file channel to access the file.
        val channel = FileChannel.open(lockFile, StandardOpenOption.APPEND)
        channel.use {
            it.lock()
            return supplier()
        }
    }

    @Throws(IOException::class)
    private fun deleteTemporaryUserDataDir0(userDataDir: Path, expiry: Duration) {
        val dirToDelete = userDataDir
        
        val cleanedUp = dirToDelete in cleanedUserDataDirs
        if (cleanedUp) {
            return
        }
        
        // Be careful, do not delete files by mistake, so delete files only inside AppPaths.CONTEXT_TMP_DIR
        // If it's in the context tmp dir, the user data dir can be deleted safely
        val isTemporary = dirToDelete.startsWith(AppPaths.CONTEXT_TMP_DIR)
        if (!isTemporary) {
            return
        }
        
        val lastModifiedTime = Files.getLastModifiedTime(dirToDelete).toInstant()
        val isExpired = DateTimes.isExpired(lastModifiedTime, expiry)
        if (!isExpired) {
            return
        }
        
        // Double check to ensure it's safe to delete the directory
        val hasSiblingPidFile = Files.exists(dirToDelete.resolveSibling(PID_FILE_NAME))
        if (!hasSiblingPidFile) {
            return
        }

        FileUtils.deleteQuietly(dirToDelete.toFile())

        if (Files.exists(dirToDelete)) {
            logger.error("Browser data dir not deleted | {}", dirToDelete)
        } else {
            cleanedUserDataDirs.add(dirToDelete)
        }
    }
    
    /**
     * Compute the next sequential context directory.
     * A typical context directory is like: /tmp/pulsar-vincent/context/group/default/cx.1
     * */
    @Throws(IOException::class)
    private fun computeNextSequentialContextDir0(group: String, maxContexts: Int): Path {
        val prefix = CONTEXT_DIR_PREFIX
        val baseDir = AppPaths.CONTEXT_GROUP_BASE_DIR
        val contextCount = computeContextCount(baseDir, prefix)
        val contextGroup = contextGroups.computeIfAbsent(group) { ContextGroup(group) }
        
        baseDir.listDirectoryEntries()
            .filter { it.isDirectory() && it.startsWith(prefix) }
            .forEach { contextGroup.add(it) }
        
        if (contextGroup.size >= maxContexts) {
            return contextGroup.iterator.next()
        }
        
        val fileName = String.format("%s%s", prefix, contextCount)
        val path = baseDir.resolve(group).resolve(fileName)
        Files.createDirectories(path)
        
        return path
    }

    /**
     * Compute a random context directory.
     * A typical context directory is like: /tmp/pulsar-vincent/context/tmp/01/cx.0109aNcTxq5
     * */
    @Throws(IOException::class)
    private fun computeRandomContextDir0(ident: String): Path {
        val prefix = CONTEXT_DIR_PREFIX
        val monthDay = MonthDay.now()
        val monthValue = monthDay.monthValue
        val dayOfMonth = monthDay.dayOfMonth
        val baseDir = AppPaths.CONTEXT_TMP_DIR.resolve("$monthValue")
        Files.createDirectories(baseDir)
        val rand = RandomStringUtils.randomAlphanumeric(5)
        val contextCount = computeContextCount(baseDir, prefix)
        val fileName = String.format("%s%02d%02d%s%s", prefix, monthValue, dayOfMonth, rand, contextCount)
        val path = baseDir.resolve(ident).resolve(fileName)
        Files.createDirectories(baseDir)
        return path
    }
    
    private fun computeContextCount(baseDir: Path, prefix: String): Long {
        return 1 + Files.list(baseDir)
            .filter { Files.isDirectory(it) }
            .filter { it.toString().contains(prefix) }
            .count()
    }
}
