package com.example

import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.IdentityHashMap
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Robolectric's Linux.open uses RandomAccessFile, which cannot open directories.
 * Exercise the real host directory fsync while leaving the Android implementation unchanged. */
@Implements(Os::class)
class DirectorySyncOsShadow {
    companion object {
        private val directories = Collections.synchronizedMap(IdentityHashMap<FileDescriptor, File>())
        @JvmStatic @Implementation
        fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            if (File(path).isDirectory && flags == android.system.OsConstants.O_RDONLY)
                return FileDescriptor().also { directories[it] = File(path) }
            return Shadow.directlyOn<FileDescriptor, Os>(Os::class.java, "open",
                ClassParameter.from(String::class.java, path), ClassParameter.from(Int::class.javaPrimitiveType, flags),
                ClassParameter.from(Int::class.javaPrimitiveType, mode))
        }
        @JvmStatic @Implementation
        fun fsync(descriptor: FileDescriptor) {
            val directory = directories[descriptor]
            if (directory != null) FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            else Shadow.directlyOn<Unit, Os>(Os::class.java, "fsync", ClassParameter.from(FileDescriptor::class.java, descriptor))
        }
        @JvmStatic @Implementation
        fun close(descriptor: FileDescriptor) {
            if (directories.remove(descriptor) == null)
                Shadow.directlyOn<Unit, Os>(Os::class.java, "close", ClassParameter.from(FileDescriptor::class.java, descriptor))
        }
    }
}
