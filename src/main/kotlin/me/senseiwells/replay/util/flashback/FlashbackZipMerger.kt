package me.senseiwells.replay.util.flashback

import io.netty.buffer.Unpooled
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

/**
 * 录像文件合并工具
 */
class FlashbackZipMerger(
    private val registryAccess: RegistryAccess
) {
    /**
     * 合并录像文件中的分片
     * @param inputZipPath 输入的录像zip文件路径
     * @param outputZipPath 输出的录像zip文件路径 (如果为null，将在同目录创建)
     * @return 输出的zip文件路径
     */
    @OptIn(ExperimentalPathApi::class)
    fun mergeZipFile(inputZipPath: Path, outputZipPath: Path? = null): Path {
        // 确保输入路径是绝对路径
        val absoluteInputPath = inputZipPath.toAbsolutePath()

        // 如果没有指定输出路径，在同目录创建
        val actualOutputPath = outputZipPath ?: run {
            val parent = absoluteInputPath.parent ?: Path.of("")  // 如果没有父目录则使用当前目录
            parent.resolve(absoluteInputPath.nameWithoutExtension + "_merged." + absoluteInputPath.extension)
        }

        // 创建临时目录
        val tempUnzipDir = Files.createTempDirectory("replay_unzip")
        val tempMergedDir = Files.createTempDirectory("replay_merged")

        try {
            println("解压录像文件...")
            // 解压录像文件
            unzipFile(absoluteInputPath, tempUnzipDir)

            println("合并分片文件...")
            // 使用FlashbackMerger合并分片
            val merger = FlashbackMerger(tempUnzipDir, registryAccess)
            val mergedDir = merger.merge()

            println("创建新的录像文件...")
            // 将合并结果压缩为新的zip文件
            zipDirectory(mergedDir, actualOutputPath)

            println("合并完成！结果保存在: $actualOutputPath")
            return actualOutputPath

        } finally {
            // 清理临时目录
            println("清理临时文件...")
            tempUnzipDir.toFile().deleteRecursively()
            tempMergedDir.toFile().deleteRecursively()
        }
    }

    // 解压文件
    private fun unzipFile(zipPath: Path, targetDir: Path) {
        ZipFile(zipPath.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryPath = targetDir.resolve(entry.name)

                if (entry.isDirectory) {
                    entryPath.createDirectories()
                } else {
                    entryPath.parent.createDirectories()
                    zip.getInputStream(entry).use { input ->
                        entryPath.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    // 压缩目录为zip文件
    private fun zipDirectory(sourceDir: Path, zipPath: Path) {
        ZipOutputStream(zipPath.outputStream()).use { zipOut ->
            sourceDir.toFile().walkTopDown().forEach { file ->
                if (!file.isDirectory) {
                    val entryPath = sourceDir.relativize(file.toPath()).toString()
                    val entry = ZipEntry(entryPath)
                    zipOut.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        }
    }
}


