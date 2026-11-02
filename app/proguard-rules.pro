# Privileged server launched by class name via app_process
-keep class io.github.akane599.umbra.server.** { *; }
# Shizuku.newProcess is called via reflection
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
