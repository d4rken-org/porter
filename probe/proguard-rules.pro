# The user service is instantiated by name inside the privileged process and never called from app
# code, so nothing keeps its constructor. Any app that binds a Porter user service needs this.
-keep class eu.darken.porter.probe.ProbeService { <init>(); }
