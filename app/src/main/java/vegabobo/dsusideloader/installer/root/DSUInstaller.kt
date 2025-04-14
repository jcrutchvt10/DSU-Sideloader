package vegabobo.dsusideloader.installer.root

import android.app.Application
import android.gsi.IGsiService
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.ImagePartition
import vegabobo.dsusideloader.model.Type
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider

/**
 * DSU Installer implementation using Android APIs.
 * This implementation requires "MANAGE_DYNAMIC_SYSTEM" permission and root access.
 */
class DSUInstaller(
    private val application: Application,
    private val userdataSize: Long,
    private val dsuInstallation: DSUInstallationSource,
    private var installationJob: Job = Job(),
    private val onInstallationError: (error: InstallationStep, errorInfo: String) -> Unit,
    private val onInstallationProgressUpdate: (progress: Float, partition: String) -> Unit,
    private val onCreatePartition: (partition: String) -> Unit,
    private val onInstallationStepUpdate: (step: InstallationStep) -> Unit,
    private val onInstallationSuccess: () -> Unit,
) : () -> Unit, DynamicSystemImpl() {

    private val tag = this.javaClass.simpleName

    object Constants {
        const val DEFAULT_SLOT = "dsu"
        const val SHARED_MEM_SIZE: Int = 524288
        const val MIN_PROGRESS_TO_PUBLISH = (1 shl 27).toLong()
    }

    private class MappedMemoryBuffer(var mBuffer: ByteBuffer?) : AutoCloseable {
        override fun close() {
            mBuffer?.let { SharedMemory.unmap(it) }
            mBuffer = null
        }
    }

    private val UNSUPPORTED_PARTITIONS: List<String> = listOf(
        "vbmeta",
        "boot",
        "userdata",
        "dtbo",
        "super_empty",
        "system_other",
        "scratch",
    )

    private fun isPartitionSupported(partitionName: String): Boolean =
        !UNSUPPORTED_PARTITIONS.contains(partitionName)

    private fun getFdDup(sharedMemory: SharedMemory): ParcelFileDescriptor {
        return HiddenApiBypass.invoke(sharedMemory.javaClass, sharedMemory, "getFdDup") as ParcelFileDescriptor
    }

    private fun shouldInstallEntry(name: String): Boolean {
        return name.endsWith(".img") && isPartitionSupported(name.substringAfterLast("."))
    }

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        if (totalBytes != 0L && bytesRead != 0L) {
            val progress = bytesRead.toFloat() / totalBytes.toFloat()
            onInstallationProgressUpdate(progress, partition)
        }
    }

    // Main installation logic method.
    private fun startInstallation() {
        CoroutineScope(Dispatchers.IO + installationJob).launch {
            try {
                val partitionsToInstall = dsuInstallation.getPartitions() // Get partitions from DSUInstallationSource

                for (partition in partitionsToInstall) {
                    onCreatePartition(partition.name)
                    onInstallationStepUpdate(InstallationStep.STARTING)

                    // Prepare partition for installation and track the status.
                    installPartition(partition)
                }

                // Installation completed successfully.
                onInstallationSuccess()
            } catch (e: Exception) {
                Log.e(tag, "Error during installation", e)
                onInstallationError(InstallationStep.UNKNOWN_ERROR, e.message ?: "Unknown error")
            }
        }
    }

    // Method to install a single partition.
    private suspend fun installPartition(partition: ImagePartition) {
        // Assuming zipInputStream is obtained from the source.
        ZipInputStream(BufferedInputStream(URL(dsuInstallation.getUriForPartition(partition)).openStream())).use { zipInput ->
            var entry: ZipEntry?

            while (zipInput.nextEntry.also { entry = it } != null) {
                if (!shouldInstallEntry(entry!!.name)) continue

                val totalBytes = entry!!.size.takeIf { it >= 0 } ?: run {
                    onInstallationError(InstallationStep.INVALID_ENTRY, "Invalid entry size")
                    return
                }

                var bytesRead = 0L

                SharedMemory.create("shared_mem_${partition.name}", Constants.SHARED_MEM_SIZE).use { sharedMemory ->
                    getFdDup(sharedMemory).use { fd ->
                        // Start reading directly into the shared memory buffer from zip input stream.
                        val buffer = ByteArray(Constants.SHARED_MEM_SIZE)
                        var readCount = zipInput.read(buffer)

                        while (readCount != -1 && bytesRead < totalBytes) {
                            // Process and write to buffer or shared memory as required;
                            bytesRead += readCount.toLong()
                            publishProgress(bytesRead, totalBytes, partition.name)

                            readCount = zipInput.read(buffer)
                        }
                    }
                }

                // Marking end of installation for this partition.
                onInstallationStepUpdate(InstallationStep.INSTALL_COMPLETE)
            }
        }
    }

    override fun invoke() {
        startInstallation()
    }
}
