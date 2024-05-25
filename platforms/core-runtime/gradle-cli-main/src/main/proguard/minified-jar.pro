# Repackage classes to avoid conflicts when the JAR is included in functional test classpath
-allowaccessmodification
-repackageclasses min

# Keep class names
-dontobfuscate

# Ignore missing symbols
-ignorewarnings

# Entry point
-keep class org.gradle.launcher.GradleMain { public static void main(java.lang.String[]); }
