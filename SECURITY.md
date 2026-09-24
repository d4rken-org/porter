---
title: Security
permalink: /security
---
# Security

## Verifying APK authenticity

You can verify a downloaded or installed APK against the fingerprints below using
[`apksigner`](https://developer.android.com/tools/apksigner) or
[AppVerifier](https://github.com/soupslurpr/AppVerifier):

```bash
apksigner verify --print-certs porter-*.apk
```

### GitHub Releases

Porter and the Porter Compatibility companion are signed with the same certificate.

Porter:

```
eu.darken.porter
C6:30:2C:2B:16:3C:7F:42:F6:D8:BD:65:8E:53:38:78:80:51:10:20:13:6A:08:3F:0A:6A:4B:DC:20:B4:BF:A0
```

Porter Compatibility:

```
moe.shizuku.privileged.api
C6:30:2C:2B:16:3C:7F:42:F6:D8:BD:65:8E:53:38:78:80:51:10:20:13:6A:08:3F:0A:6A:4B:DC:20:B4:BF:A0
```

The companion uses the original Shizuku package name. The original Shizuku app has the same package
name but a different certificate, so it will not match.

All fingerprints of the signing certificate:

```
SHA-256: C6:30:2C:2B:16:3C:7F:42:F6:D8:BD:65:8E:53:38:78:80:51:10:20:13:6A:08:3F:0A:6A:4B:DC:20:B4:BF:A0
SHA-1:   DC:74:2B:F6:3B:BA:59:53:71:83:FB:22:09:33:09:E5:14:C8:0A:53
MD5:     A0:A9:80:92:1B:2B:F5:66:8F:ED:EE:4B:65:F2:E4:1C
```
