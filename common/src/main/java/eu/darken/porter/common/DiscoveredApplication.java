package eu.darken.porter.common;

import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.BadParcelableException;
import android.os.Parcelable;

/** Manager-only discovery entry. Wire values are independent of the Shizuku API. */
public final class DiscoveredApplication implements Parcelable {
    public static final int TRANSACTION = 10003;
    public static final int WIRE_VERSION = 1;
    public static final int API_PORTER = 1;
    public static final int API_SHIZUKU = 2;
    public static final int DEFAULT = 0;
    public static final int ALLOWED = 1;
    public static final int DENIED = 2;
    public static final int DIRECT = 1;
    public static final int COMPANION = 2;
    public static final int NEEDS_COMPANION = 3;
    public static final int UNSUPPORTED = 4;
    public static final int MANAGED_ONLY = 5;
    public static final int UNKNOWN = 0;

    public final ApplicationInfo applicationInfo;
    public final int userId;
    public final int declaredApis;
    public final int authorization;
    public final int connectionStatus;
    public final boolean requiresRoot;
    /** Epoch milliseconds; zero means no recorded connection. */
    public final long lastConnectedAt;

    public DiscoveredApplication(ApplicationInfo info, int userId, int apis, int authorization,
            int connectionStatus, boolean requiresRoot, long lastConnectedAt) {
        this.applicationInfo = info;
        this.userId = userId;
        this.declaredApis = apis;
        this.authorization = authorization;
        this.connectionStatus = connectionStatus;
        this.requiresRoot = requiresRoot;
        this.lastConnectedAt = lastConnectedAt;
    }

    private DiscoveredApplication(Parcel in) {
        int start = in.dataPosition();
        int size = in.readInt();
        if (size < 4 || size > in.dataSize() - start) throw new BadParcelableException("Invalid discovery entry size");
        int end = start + size;
        applicationInfo = ApplicationInfo.CREATOR.createFromParcel(in);
        userId = in.readInt();
        declaredApis = in.readInt();
        authorization = in.readInt();
        connectionStatus = in.readInt();
        requiresRoot = in.readInt() != 0;
        lastConnectedAt = in.readLong();
        if (in.dataPosition() > end) throw new BadParcelableException("Truncated discovery entry");
        in.setDataPosition(end);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        int start = out.dataPosition();
        out.writeInt(0);
        applicationInfo.writeToParcel(out, flags);
        out.writeInt(userId);
        out.writeInt(declaredApis);
        out.writeInt(authorization);
        out.writeInt(connectionStatus);
        out.writeInt(requiresRoot ? 1 : 0);
        out.writeLong(lastConnectedAt);
        int end = out.dataPosition();
        out.setDataPosition(start);
        out.writeInt(end - start);
        out.setDataPosition(end);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<DiscoveredApplication> CREATOR = new Creator<>() {
        @Override public DiscoveredApplication createFromParcel(Parcel in) { return new DiscoveredApplication(in); }
        @Override public DiscoveredApplication[] newArray(int size) { return new DiscoveredApplication[size]; }
    };
}
