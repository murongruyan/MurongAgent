package com.murong.agent.common.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * APK Assets 提取器
 * 将 assets 中的 Node.js runtime / 其他资源解压到 App 数据目录
 */
object AssetExtractor {

    /**
     * 从 assets 复制文件/目录到目标路径
     */
    fun extractAsset(context: Context, assetPath: String, destDir: File): File {
        val destFile = File(destDir, assetPath)
        destFile.parentFile?.mkdirs()

        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }

        // 设置可执行权限（针对二进制文件）
        if (!assetPath.endsWith(".txt") && !assetPath.endsWith(".json")) {
            destFile.setExecutable(true)
        }

        return destFile
    }

    /**
     * 递归提取 assets 目录
     */
    fun extractAssetDir(context: Context, assetDir: String, destDir: File) {
        val assets = context.assets.list(assetDir) ?: return

        for (asset in assets) {
            val fullAssetPath = "$assetDir/$asset"
            try {
                // 尝试作为文件打开——成功说明是文件
                context.assets.open(fullAssetPath).use {}
                extractAsset(context, fullAssetPath, destDir)
            } catch (e: Exception) {
                // 打不开则是目录，递归
                val subDir = File(destDir, fullAssetPath)
                subDir.mkdirs()
                extractAssetDir(context, fullAssetPath, destDir)
            }
        }
    }
}
