# Contributing

Thanks for wanting to help. Bug reports, fixes, translations and support for new Minecraft versions are all welcome.

## Reporting a bug

Open an issue with:

- the server software and version (`/version`) and the Mod Utils version (`/modutils status`)
- the client mod, its version and the client's Minecraft version
- what you expected and what happened, with the **full stack trace** from `logs/latest.log` if there is one
- your `config.yml` section for the feature, if it is about one

## Setting up

You need JDK 25. Then:

```bash
./gradlew build                      # compiles every adapter
./gradlew :plugin:shadowJar          # builds plugin/build/libs/ModUtils-<version>.jar
./gradlew :plugin:runServer-1.21.11  # a Paper test server with the plugin
```

Open the project in IntelliJ IDEA as a Gradle project. `common/` is compiled against one Minecraft version at a time for
the IDE (`ideVersion` in `gradle.properties`).

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains the modules and how a per-version adapter is put together.

## Making a change

1. Fork the repository and make a branch for your change.
2. Keep the style of the code around yours: tabs, `this.` on fields, short Javadoc on the public bits, comments that say
   *why*.
3. Code in `shared/` and `api/` must only use the Bukkit API, so it runs on Spigot and Paper. Anything that needs
   Minecraft internals goes in `common/` or a version adapter.
4. Make sure `./gradlew build` passes. If you changed anything in an adapter, build all versions, not only the one you
   test on.
5. If you changed behaviour, add it to the README or the config comments, and put new messages in
   `translations/en_US.yml` rather than in code.
6. Open a pull request that says what changed and how you tested it (server version, client mod and version).

## Adding a translation

Copy `plugins/ModUtils/translations/en_US.yml`, name it after the locale (for example `de_DE.yml`), and translate the
values. Keys and `<placeholders>` stay as they are.

## Supporting a new Minecraft version

See "When a new Minecraft version breaks the build" in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License

By contributing you agree that your work is released under the project's license, [CC BY-NC-SA 4.0](LICENSE.md).
