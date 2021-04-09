package ai.platon.pulsar.crawl.index

import ai.platon.pulsar.common.Strings
import java.lang.AutoCloseable
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.crawl.index.IndexDocument
import java.util.ArrayList
import java.util.stream.Collectors

/**
 * Creates [IndexWriter] implementing plugins.
 */
class IndexWriters(
    val indexWriters: MutableList<IndexWriter> = mutableListOf(),
    val conf: ImmutableConfig
) : AutoCloseable {
    var LOG = getLogger(IndexWriters::class)

    constructor(conf: ImmutableConfig) : this(mutableListOf(), conf)

    fun open() {
        for (indexWriter in indexWriters) {
            try {
                indexWriter.open(conf)
            } catch (e: Throwable) {
                LOG.error("Failed to open indexer. " + Strings.stringifyException(e))
            }
        }
    }

    fun open(indexerUrl: String?) {
        for (indexWriter in indexWriters) {
            try {
                indexWriter.open(indexerUrl)
            } catch (e: Throwable) {
                LOG.error("Failed to open indexer. " + Strings.stringifyException(e))
            }
        }
    }

    fun write(doc: IndexDocument?) {
        for (indexWriter in indexWriters) {
            try {
                indexWriter.write(doc)
            } catch (e: Throwable) {
                LOG.error("Failed to write indexer. " + Strings.stringifyException(e))
            }
        }
    }

    fun update(doc: IndexDocument) {
        for (indexWriter in indexWriters) {
            try {
                indexWriter.update(doc)
            } catch (e: Throwable) {
                LOG.error("Failed to update indexer. " + Strings.stringifyException(e))
            }
        }
    }

    fun delete(key: String) {
        for (indexWriter in indexWriters) {
            try {
                indexWriter.delete(key)
            } catch (e: Throwable) {
                LOG.error("Failed to delete indexer. " + Strings.stringifyException(e))
            }
        }
    }

    override fun close() {
        for (indexWriter in indexWriters) {
            try {
                // log.info("[Destruction] Closing IndexWriter " + indexWriter.getName() + ", ...");
                indexWriter.close()
            } catch (e: Throwable) {
                LOG.error("Failed to close IndexWriter " + indexWriter.name)
                LOG.error(Strings.stringifyException(e))
            }
        }
        indexWriters.clear()
    }

    fun commit() {
        for (indexWriter in indexWriters) {
            try {
                indexWriter.commit()
            } catch (e: Throwable) {
                LOG.error("Failed to commit indexer. " + Strings.stringifyException(e))
            }
        }
    }

    override fun toString(): String {
        return indexWriters.joinToString { it.javaClass.name }
    }
}