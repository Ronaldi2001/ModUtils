# tools

## ApiCheck

Lists the Bukkit, Paper and Minecraft methods, constructors and fields that a built adapter calls but a given server does
not have. Run it on the Bukkit adapters (`bukkit/<version>`) against the Spigot jar of the same version before you try them
on a server: it finds in one go what would otherwise show up one `NoSuchMethodError` at a time.

```
java tools/ApiCheck.java <server jar> <libraries dir> <jar to check>...
```

Needs Java 25 (it reads class files with `java.lang.classfile`). For a Spigot server built by BuildTools and started once,
the server jar is `bundler/versions/spigot-<version>-R0.1-SNAPSHOT.jar` and the libraries are in `bundler/libraries`:

```
java tools/ApiCheck.java C:/bukkitTest/1.21.11/bundler/versions/spigot-1.21.11-R0.2-SNAPSHOT.jar C:/bukkitTest/1.21.11/bundler/libraries bukkit/v1_21_11/build/libs/v1_21_11-1.1-SNAPSHOT.jar shared/build/libs/shared-1.1-SNAPSHOT.jar bukkit/build/libs/bukkit-1.1-SNAPSHOT.jar
```

Everything it prints is a call the server cannot answer. Once the Bukkit adapter is clean, only the count line is left.
