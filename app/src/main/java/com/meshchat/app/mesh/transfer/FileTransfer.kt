package com.meshchat.app.mesh.transfer

import java.io.File

enum class TransferDirection { SENDING, RECEIVING }
enum class TransferStatus { RUNNING, DONE, FAILED }

data class FileProgress(
    val fileId: String,
    val convId: String,
    val direction: TransferDirection,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: TransferStatus,
)

/** 接收端保存接口：Android 实现写 MediaStore.Downloads；测试用临时目录。 */
interface FileSaver {
    fun save(tmpFile: File, fileName: String, mime: String): String?
}
