# LC2H platform layout

The repository root contains the shared LC2H implementation and the build
orchestrator. Loader-specific code stays under its platform directory:

- `src/main/java` and `src/main/resources` contain loader-neutral code and
  shared assets.
- `platforms/forge/1.20.1` contains the Forge entrypoint, Forge mixins,
  resources, and the ForgeGradle build.
- `platforms/neoforge/1.21.1` contains the NeoForge compatibility layer and
  its NeoGradle build.

From the root, use `buildForge1201`, `buildNeoForge1211`, or
`buildOmniJar`. The latter combines both platform payloads into one jar.
Platform run directories and build output are ignored and should not be
committed.
