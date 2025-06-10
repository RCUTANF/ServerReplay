package me.senseiwells.replay.util.flashback

import io.netty.buffer.Unpooled
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

import me.senseiwells.replay.writer.flashback.FlashbackChunkedWriter

/**
 * 工具类，用于将多个分片的flashback文件合并为一个
 */
class FlashbackMerger(
    private val sourceDir: Path,
    private val registryAccess: RegistryAccess
) {
    private val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess)

    /**
     * 执行合并操作
     */
    @OptIn(ExperimentalPathApi::class)
    fun merge(): Path {
        try {
            // 读取元数据
            val metaFile = sourceDir.resolve(FlashbackIO.METADATA)
            if (!metaFile.exists()) {
                throw IllegalStateException("元数据文件不存在，无法合并")
            }

            // 解析元数据
            println("读取录像元数据...")
            val meta = readMetadata(metaFile)
            val sortedChunks = meta.chunks.entries.sortedBy {
                // 从文件名中提取数字序号，按序号排序
                it.key.removePrefix("c").removeSuffix(".flashback").toIntOrNull() ?: 0
            }

            if (sortedChunks.isEmpty()) {
                throw IllegalStateException("没有找到分块文件")
            }

            // 创建临时目录存放合并结果
            val tempDir = Files.createTempDirectory("flashback_merged")
            val writer = FlashbackChunkedWriter(tempDir, registryAccess)

            // 收集所有分块的快照动作
            println("开始收集所有分片的快照数据...")
            val allSnapshotActions = mutableListOf<Triple<FlashbackAction, ByteArray, Int>>() // 动作、数据、来源块ID

            // 遍历所有分片，收集快照数据
            for ((index, chunk) in sortedChunks.withIndex()) {
                val chunkPath = sourceDir.resolve(chunk.key)
                println("处理分片 ${chunk.key} 的快照数据...")
                val chunkHeader = readChunkHeader(chunkPath)
                val snapshotActions = readChunkSnapshotData(chunkHeader, index)
                allSnapshotActions.addAll(snapshotActions)
            }

            // 开始构建合并的数据
            println("合并快照数据...")
            writer.startSnapshot()
            for ((action, data, _) in allSnapshotActions) {
                writer.writeAction(action) { it.writeBytes(data) }
            }
            writer.endSnapshot()

            var totalTicks = 0
            val mergedEvents = mutableListOf<Pair<FlashbackAction, ByteArray>>()

            // 读取并合并所有分块的事件数据
            println("合并事件数据...")
            for ((chunkName, chunkMeta) in sortedChunks) {
                val chunkPath = sourceDir.resolve(chunkName)
                if (!chunkPath.exists()) {
                    throw IllegalStateException("分块文件不存在: $chunkName")
                }

                println("处理分片 $chunkName 的事件数据...")
                val chunkHeader = readChunkHeader(chunkPath)
                val chunkEvents = readChunkEventsData(chunkHeader)
                mergedEvents.addAll(chunkEvents)

                totalTicks += chunkMeta.duration
            }

            // 写入合并的事件数据
            for ((action, data) in mergedEvents) {
                writer.writeAction(action) { buf ->
                    buf.writeBytes(data)
                }
            }

            // 完成分块写入
            writer.endChunk(totalTicks)

            // 更新元数据
            println("更新元数据...")
            val newMeta = FlashbackMeta(
                chunks = mapOf("c0.flashback" to FlashbackMeta.ChunkMeta(totalTicks, false)),
                markers = meta.markers
            )

            writeMetadata(tempDir.resolve(FlashbackIO.METADATA), newMeta)

            // 复制区块缓存
            println("复制区块缓存...")
            val cacheDir = sourceDir.resolve(FlashbackIO.CHUNK_CACHES)
            val targetDir = tempDir.resolve(FlashbackIO.CHUNK_CACHES)
            if (cacheDir.exists()) {
                if (!targetDir.exists()) {
                    targetDir.createDirectories()
                }
                cacheDir.listDirectoryEntries().forEach { source ->
                    val target = targetDir.resolve(source.name)
                    if (source.isDirectory()) {
                        source.copyToRecursively(target, followLinks = false)
                    } else {
                        source.copyTo(target)
                    }
                }
            }

            println("合并完成！")
            return tempDir
        } finally {
            buffer.release()
        }
    }

    /**
     * 读取并解析分块文件的头部信息
     * 包括魔数、动作表和快照大小
     */
    private fun readChunkHeader(chunkPath: Path): ChunkHeader {
        val bytes = chunkPath.readBytes()
        buffer.clear()
        buffer.writeBytes(bytes)
        buffer.readerIndex(0)

        // 检查魔数
        val magic = buffer.readInt()
        if (magic != FlashbackIO.MAGIC_NUMBER) {
            throw IllegalStateException("无效的分块文件格式")
        }

        // 读取动作类型表
        val actionCount = buffer.readVarInt()
        val actions = mutableMapOf<Int, FlashbackAction>()
        for (i in 0..<actionCount) {
            val id = buffer.readResourceLocation()
            val action = FlashbackAction.from(id)
            if (action != null) {
                actions[i] = action
            }
        }

        // 获取快照大小
        val snapshotSize = buffer.readInt()
        val snapshotStart = buffer.readerIndex()
        val snapshotEnd = snapshotStart + snapshotSize

        return ChunkHeader(actions, snapshotStart, snapshotEnd)
    }

    /**
     * 读取分块文件中的快照数据
     */
    private fun readChunkSnapshotData(header: ChunkHeader, chunkIndex: Int): List<Triple<FlashbackAction, ByteArray, Int>> {
        buffer.readerIndex(header.snapshotStart)

        // 收集快照中的所有动作
        val snapshotActions = mutableListOf<Triple<FlashbackAction, ByteArray, Int>>()

        while (buffer.readerIndex() < header.snapshotEnd) {
            val actionId = buffer.readVarInt()
            val action = header.actions[actionId] ?: continue

            val size = buffer.readInt()
            val actionData = ByteArray(size)
            buffer.readBytes(actionData)

            snapshotActions.add(Triple(action, actionData, chunkIndex))
        }

        return snapshotActions
    }

    /**
     * 读取分块文件中的事件数据
     */
    private fun readChunkEventsData(header: ChunkHeader): List<Pair<FlashbackAction, ByteArray>> {
        // 跳过快照部分，直接定位到事件数据起始位置
        buffer.readerIndex(header.snapshotEnd)

        // 读取事件数据
        val events = mutableListOf<Pair<FlashbackAction, ByteArray>>()
        while (buffer.readerIndex() < buffer.writerIndex()) {
            val actionId = buffer.readVarInt()
            val action = header.actions[actionId] ?: continue

            val size = buffer.readInt()
            val data = ByteArray(size)
            buffer.readBytes(data)

            events.add(Pair(action, data))
        }

        return events
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readMetadata(path: Path): FlashbackMeta {
        val json = Json {
            ignoreUnknownKeys = true  // 添加此配置以忽略未知字段
        }

        path.inputStream().use {
            return json.decodeFromStream(it)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeMetadata(path: Path, meta: FlashbackMeta) {
        path.outputStream().use {
            Json.encodeToStream(meta, it)
        }
    }

    /**
     * 保存分块文件的头部信息
     */
    private data class ChunkHeader(
        val actions: Map<Int, FlashbackAction>,
        val snapshotStart: Int,
        val snapshotEnd: Int
    )
}