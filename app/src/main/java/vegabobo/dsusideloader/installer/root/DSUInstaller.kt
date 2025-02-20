package vegabobo.dsusideloader.installer.root

import android.app.Application
import android.gsi.IGsiService
import android.util.Log
import kotlinx.coroutines.*
import org.lsposed.hiddenapibypass.HiddenApiBypass
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.ImagePartition
import vegabobo.dsusideloader.model.Type
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipInputStream

// Modernized for compatibility with the latest Android SDK and coroutines best practices
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

    override fun invoke() {
        startInstallation()
    }

    private fun startInstallation() {
        PrivilegedProvider.getService().setDynProp()
        
        if (isInUse) {
            handleInstallationError(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS)
            return
        }

        if (isInstalled) {
            handleInstallationError(InstallationStep.ERROR_REQUIRES_DISCARD_DSU)
            return
        }

        forceStopDSU()

        startInstallation(Constants.DEFAULT_SLOT)

        installWritablePartition("userdata", userdataSize)

        when (dsuInstallation.type) {
            Type.SINGLE_SYSTEM_IMAGE -> {
                installImage("system", dsuInstallation.fileSize, dsuInstallation.uri)
            }
            Type.MULTIPLE_IMAGES -> {
                installImages(dsuInstallation.images)
            }
            Type.DSU_PACKAGE -> {
                installStreamingZipUpdate(openInputStream(dsuInstallation.uri))
            }
            Type.URL -> {
                val url = URL(dsuInstallation.uri.toString())
                installStreamingZipUpdate(url.openStream())
            }
            else -> {}
        }

        if (!installationJob.isCancelled) {
            finishInstallation()
            Log.d(tag, "Installation finished successfully.")
            onInstallationSuccess()
        }
    }

    private fun installImages(images: List<ImagePartition>) {
        images.forEach { image ->
            if (isPartitionSupported(image.partitionName)) {
                installImage(image.partitionName, image.fileSize, image.uri)
            }
            if (installationJob.isCancelled) {
                remove()
            }
        }
    }

    private fun installImage(partitionName: String, uncompressedSize: Long, uri: Uri) {
        installImage(partitionName, uncompressedSize, openInputStream(uri))
        if (installationJob.isCancelled) {
            remove()
        }
    }

    private fun installImage(
        partition: String,
        uncompressedSize: Long,
        inputStream: InputStream,
        readOnly: Boolean = true,
    ) = runBlocking {
        val bufferedInputStream = BufferedInputStream(inputStream)
        val sis = SparseInputStream(bufferedInputStream)
        
        val partitionSize = sis.unsparseSize.takeIf { it != -1L } ?: uncompressedSize
        
        onCreatePartition(partition)
        createNewPartition(partition, partitionSize, readOnly)
        onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED)
        
        SharedMemory.create("dsu_buffer_$partition", Constants.SHARED_MEM_SIZE).use { sharedMemory ->
            MappedMemoryBuffer(sharedMemory.mapReadWrite()).use { mappedBuffer ->
                val fdDup = getFdDup(sharedMemory)
                setAshmem(fdDup, sharedMemory.size.toLong())
                publishProgress(0L, partitionSize, partition)
                
                var installedSize: Long = 0
                val readBuffer = ByteArray(sharedMemory.size)
                val buffer = mappedBuffer.mBuffer
                
                while (sis.read(readBuffer, 0, readBuffer.size) != -1) {
                    if (installationJob.isCancelled) return@runBlocking

                    buffer.position(0)
                    buffer.put(readBuffer)
                    submitFromAshmem(readBuffer.size.toLong())
                    installedSize += readBuffer.size.toLong()
                    publishProgress(installedSize, partitionSize, partition)
                }
                
                publishProgress(partitionSize, partitionSize, partition)
            }
        }
    }

    fun openInputStream(uri: Uri): InputStream {
        return application.contentResolver.openInputStream(uri)!!
    }

    fun createNewPartition(partition: String, partitionSize: Long, readOnly: Boolean) {
        val result = createPartition(partition, partitionSize, readOnly)
        if (result != IGsiService.INSTALL_OK) {
            Log.d(
                tag,
                "Failed to create $partition partition, error code: $result (check: IGsiService.INSTALL_*)"
            )
            installationJob.cancel()
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
        }
    }

    private fun handleInstallationError(errorStep: InstallationStep) {
        onInstallationError(errorStep, "")
    }
}
