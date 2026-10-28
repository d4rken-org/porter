package eu.darken.porter.manager.compatibility

import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Lets Robolectric verify an APK's v2/v3 signature. The framework wraps its mapping in
 * java.nio.DirectByteBuffer, which is public on Android but package-private in OpenJDK, so
 * getPackageArchiveInfo with GET_SIGNING_CERTIFICATES throws IllegalAccessError
 * (Robolectric 4.16.1, android-all 14). This digests the same file range from a heap buffer.
 */
@Implements(className = "android.util.apk.MemoryMappedFileDataSource", isInAndroidSdk = false)
class ShadowMemoryMappedFileDataSource {
    @RealObject private lateinit var real: Any

    @Implementation
    fun feedIntoDataDigester(@ClassName("android.util.apk.DataDigester") digester: Any, offset: Long, size: Int) {
        val fd = ReflectionHelpers.getField<FileDescriptor>(real, "mFd")
        val start = ReflectionHelpers.getField<Long>(real, "mFilePosition") + offset
        val buffer = ByteBuffer.allocate(size)
        // Not closed: the descriptor belongs to the verifier.
        val channel = FileInputStream(fd).channel
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, start + buffer.position()) < 0) throw EOFException()
        }
        buffer.flip()
        ReflectionHelpers.callInstanceMethod<Any?>(digester, "consume", ClassParameter.from(ByteBuffer::class.java, buffer))
    }
}
