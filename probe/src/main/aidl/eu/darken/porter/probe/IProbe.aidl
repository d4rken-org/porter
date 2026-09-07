package eu.darken.porter.probe;

interface IProbe {
    // Shizuku user-service destroy contract: transaction 16777115 (16777114 in aidl).
    void destroy() = 16777114;
    int uid() = 1;
    String readFile(String path) = 2;
}
