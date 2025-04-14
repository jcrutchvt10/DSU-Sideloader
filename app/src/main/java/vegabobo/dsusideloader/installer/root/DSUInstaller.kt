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
import kotlinx.coroutines.*
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
        const val SHARED_MEM_SIZE = 524288
        const val MIN_PROGRESS_TO_PUBLISH = (1 shl 27).toLong()
    }

    private class MappedMemoryBuffer(var mBuffer: ByteBuffer?) : AutoCloseable {
        override fun close() {
            mBuffer?.let { SharedMemory.unmap(it) }
            mBuffer = null
        }
    }

    private val UNSUPPORTED_PARTITIONS = listOf(
        "vbmeta", "boot", "userdata", "dtbo",
        "super_empty", "system_other", "scratch"
    )

    private fun isPartitionSupported(name: String): Boolean =
        !UNSUPPORTED_PARTITIONS.contains(name)

    private fun getFdDup(sharedMemory: SharedMemory): ParcelFileDescriptor {
        return HiddenApiBypass.invoke(sharedMemory.javaClass, sharedMemory, "getFdDup") as ParcelFileDescriptor
    }

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        if (totalBytes != 0L && bytesRead != 0L) {
            val progress = bytesRead.toFloat() / totalBytes.toFloat()
            onInstallationProgressUpdate(progress, partition)
        }
    }

    private fun shouldInstallEntry(name: String): Boolean {
        return name.endsWith(".img") && isPartitionSupported(name.substringBeforeLast("."))
    }

    private fun createNewPartition(partition: String, size: Long, readOnly: Boolean = true) {
        val result = createPartition(partition, size, readOnly)
        if (result != IGsiService.INSTALL_OK) {
            Log.e(tag, "Failed to create $partition partition, error code: $result")
            installationJob.cancel()
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
        }
    }

    private fun installImage(partition: String, size: Long, input: InputStream, readOnly: Boolean = true) {
        onCreatePartition(partition)
        onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED)

        SharedMemory.create("dsu_buffer_$partition", Constants.SHARED_MEM_SIZE).use { sharedMem ->
            MappedMemoryBuffer(sharedMem.mapReadWrite()).use { mapped ->
                val buffer = mapped.mBuffer ?: return
                val fd = getFdDup(sharedMem)
                setAshmem(fd, sharedMem.size.toLong())

                val byteBuffer = ByteArray(sharedMem.size)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(byteBuffer).also { bytesRead = it } != -1) {
                    if (installationJob.isCancelled) return
                    buffer.position(0)
                    buffer.put(byteBuffer, 0, bytesRead)
                    submitFromAshmem(bytesRead.toLong())
                    totalRead += bytesRead
                    publishProgress(totalRead, size, partition)
                }

                publishProgress(size, size, partition)
            }
        }

        if (!closePartition()) {
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
            return
        }

        Log.d(tag, "Installed $partition ($size bytes)")
    }

    private fun installZip(uri: Uri) {
        val stream = application.contentResolver.openInputStream(uri) ?: run {
            onInstallationError(InstallationStep.INVALID_ENTRY, "Could not open ZIP stream")
            return
        }

        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null && !installationJob.isCancelled) {
                val name = entry!!.name
                if (shouldInstallEntry(name)) {
                    val partName = name.substringBeforeLast(".img")
                    val size = entry!!.size
                    if (size <= 0) {
                        onInstallationError(InstallationStep.INVALID_ENTRY, "Invalid entry size: $name")
                        return
                    }
                    installImage(partName, size, zip)
                } else {
                    Log.d(tag, "Skipping unsupported entry: $name")
                }
            }
        }
    }

    private fun installImages(images: List<ImagePartition>) {
        for (image in images) {
            if (!isPartitionSupported(image.partitionName)) continue
            installImage(image.partitionName, image.fileSize, openInputStream(image.uri))
            if (installationJob.isCancelled) {
                remove()
                break
            }
        }
    }

    private fun openInputStream(uri: Uri): InputStream {
        return application.contentResolver.openInputStream(uri)!!
    }

    private fun startInstallation() {
        CoroutineScope(Dispatchers.IO + installationJob).launch {
            try {
                PrivilegedProvider.getService().setDynProp()

                if (isInUse) {
                    onInstallationError(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, "")
                    return@launch
                }
                if (isInstalled) {
                    onInstallationError(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, "")
                    return@launch
                }

                forceStopDSU()
                startInstallation(Constants.DEFAULT_SLOT)

                installWritablePartition("userdata", userdataSize)

                when (dsuInstallation.type) {
                    Type.SINGLE_SYSTEM_IMAGE -> {
                        installImage("system", dsuInstallation.fileSize, openInputStream(dsuInstallation.uri))
                    }
                    Type.MULTIPLE_IMAGES -> {
                        installImages(dsuInstallation.images)
                    }
                    Type.DSU_PACKAGE -> {
                        installZip(dsuInstallation.uri)
                    }
                    Type.URL -> {
                        installZipFromUrl(dsuInstallation.uri.toString())
                    }
                    else -> {}
                }

                if (!installationJob.isCancelled) {
                    finishInstallation()
                    onInstallationSuccess()
                }
            } catch (e: Exception) {
                Log.e(tag, "Installation failed", e)
                onInstallationError(InstallationStep.UNKNOWN_ERROR, e.message ?: "Unknown error")
            }
        }
    }

    private fun installZipFromUrl(urlStr: String) {
        val url = URL(urlStr)
        installZip(Uri.parse(url.toURI().toString()))
    }

    private fun installWritablePartition(partition: String, size: Long) {
        onCreatePartition(partition)
        createNewPartition(partition, size, readOnly = false)
        publishProgress(0L, size, partition)
        onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED)
        // In real DSU, system writes something to userdata partition via installer binary
        closePartition()
    }

    override fun invoke() {
        startInstallation()
    }
}
