package eu.darken.porter.common

import android.content.pm.ApplicationInfo
import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable

/** Manager-only discovery entry. Wire values are independent of the Shizuku API. */
class DiscoveredApplication(
    val applicationInfo: ApplicationInfo,
    val userId: Int,
    val declaredApis: Int,
    val authorization: Int,
    val connectionStatus: Int,
    val requiresRoot: Boolean,
    /** Epoch milliseconds; zero means no recorded connection. */
    val lastConnectedAt: Long,
) : Parcelable {

    private constructor(source: Parcel, start: Int, end: Int) : this(
        ApplicationInfo.CREATOR.createFromParcel(source),
        source.readInt(),
        source.readInt(),
        source.readInt(),
        source.readInt(),
        source.readInt() != 0,
        source.readLong(),
    ) {
        if (source.dataPosition() > end) throw BadParcelableException("Truncated discovery entry")
        source.setDataPosition(end)
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
        val start = out.dataPosition()
        out.writeInt(0)
        applicationInfo.writeToParcel(out, flags)
        out.writeInt(userId)
        out.writeInt(declaredApis)
        out.writeInt(authorization)
        out.writeInt(connectionStatus)
        out.writeInt(if (requiresRoot) 1 else 0)
        out.writeLong(lastConnectedAt)
        val end = out.dataPosition()
        out.setDataPosition(start)
        out.writeInt(end - start)
        out.setDataPosition(end)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val TRANSACTION = AppTransactions.DISCOVER_APPLICATIONS
        const val WIRE_VERSION = 1
        const val API_PORTER = 1
        const val API_SHIZUKU = 2
        const val DEFAULT = 0
        const val ALLOWED = 1
        const val DENIED = 2
        const val PENDING_COMPANION = 3
        const val DIRECT = 1
        const val COMPANION = 2
        const val NEEDS_COMPANION = 3
        const val UNSUPPORTED = 4
        const val MANAGED_ONLY = 5
        const val UNKNOWN = 0

        /** Reads the size prefix first, so an entry from a newer sender can be skipped whole. */
        private fun readFrom(source: Parcel): DiscoveredApplication {
            val start = source.dataPosition()
            val size = source.readInt()
            if (size < 4 || size > source.dataSize() - start) throw BadParcelableException("Invalid discovery entry size")
            return DiscoveredApplication(source, start, start + size)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<DiscoveredApplication> = object : Parcelable.Creator<DiscoveredApplication> {
            override fun createFromParcel(source: Parcel): DiscoveredApplication = readFrom(source)
            override fun newArray(size: Int): Array<DiscoveredApplication?> = arrayOfNulls(size)
        }
    }
}
