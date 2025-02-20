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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.ImagePartition
import vegabobo.dsusideloader.model.Type
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider

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
            mBuffer?.let {
                SharedMemory.unmap(it)
                mBuffer = null
            }
        }
    }

    private val unsupportedPartitions = listOf(
        "vbmeta", "boot", "userdata", "dtbo", "super_empty", "system_other", "scratch"
    )

    private fun isPartitionSupported(partitionName: String) = partitionName !in unsupportedPartitions

    private fun getFdDup(sharedMemory: SharedMemory): ParcelFileDescriptor {
        return HiddenApiBypass.invoke(
            sharedMemory.javaClass,
            sharedMemory,
            "getFdDup"
        ) as ParcelFileDescriptor
    }

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        val progress = if (totalBytes != 0L) bytesRead.toFloat() / totalBytes.toFloat() else 0F
        onInstallationProgressUpdate(progress, partition)
    }

    private fun installWritablePartition(partition: String, partitionSize: Long, readOnly: Boolean = false) {
        val job = Job()
        CoroutineScope(Dispatchers.IO + job).launch {
            createNewPartition(partition, partitionSize, readOnly)
            job.complete()
        }
        publishProgress(0L, partitionSize, partition)
    }

    private fun installImage(partition: String, uncompressedSize: Long, inputStream: InputStream, readOnly: Boolean = true) {
        val sis = SparseInputStream(BufferedInputStream(inputStream))
        val partitionSize = sis.unsparseSize.takeIf { it != -1L } ?: uncompressedSize

        onCreatePartition(partition)
        createNewPartition(partition, partitionSize, readOnly)
        onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED)

        SharedMemory.create("dsu_buffer_$partition", Constants.SHARED_MEM_SIZE).use { sharedMemory ->
            MappedMemoryBuffer(sharedMemory.mapReadWrite()).use { mappedBuffer ->
                val fdDup = getFdDup(sharedMemory)
                setAshmem(fdDup, sharedMemory.size.toLong())
                publishProgress(0L, partitionSize, partition)
                var installedSize = 0L
                val readBuffer = ByteArray(sharedMemory.size)
                val buffer = mappedBuffer.mBuffer
                var numBytesRead: Int
                while (0 < sis.read(readBuffer, 0, readBuffer.size).also { numBytesRead = it }) {
                    if (installationJob.isCancelled) return
                    buffer!!.position(0)
                    buffer.put(readBuffer, 0, numBytesRead)
                    submitFromAshmem(numBytesRead.toLong())
                    installedSize += numBytesRead.toLong()
                    publishProgress(installedSize, partitionSize, partition)
                }
                publishProgress(partitionSize, partitionSize, partition)
            }
        }
    }

    private fun installStreamingZipUpdate(inputStream: InputStream): Boolean {
        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry?
        while (zis.nextEntry.also { entry = it } != null) {
            val fileName = entry!!.name
            if (fileName.endsWith(".img")) {
                val partitionName = fileName.substringBeforeLast(".")
                installImage(partitionName, entry!!.size, zis)
            }
            if (installationJob.isCancelled) break
        }
        return true
    }

    private fun startInstallation() {
        PrivilegedProvider.getService().setDynProp()
        if (isInUse) {
            onInstallationError(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, "")
            return
        }
        if (isInstalled) {
            onInstallationError(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, "")
            return
        }
        forceStopDSU()
        startInstallation(Constants.DEFAULT_SLOT)
        installWritablePartition("userdata", userdataSize)

        when (dsuInstallation.type) {
            Type.SINGLE_SYSTEM_IMAGE -> installImage("system", dsuInstallation.fileSize, openInputStream(dsuInstallation.uri))
            Type.MULTIPLE_IMAGES -> dsuInstallation.images.forEach { installImage(it.partitionName, it.fileSize, openInputStream(it.uri)) }
            Type.DSU_PACKAGE -> installStreamingZipUpdate(openInputStream(dsuInstallation.uri))
            Type.URL -> installStreamingZipUpdate(URL(dsuInstallation.uri.toString()).openStream())
            else -> {}
        }

        if (!installationJob.isCancelled) {
            finishInstallation()
            Log.d(tag, "Installation finished successfully.")
            onInstallationSuccess()
        }
    }

    private fun openInputStream(uri: Uri): InputStream = application.contentResolver.openInputStream(uri)!!

    private fun createNewPartition(partition: String, partitionSize: Long, readOnly: Boolean) {
        val result = createPartition(partition, partitionSize, readOnly)
        if (result != IGsiService.INSTALL_OK) {
            Log.d(tag, "Failed to create $partition partition, error code: $result")
            installationJob.cancel()
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
        }
    }

    override fun invoke() {
        startInstallation()
    }
}
