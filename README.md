# LC2H | Lost Cities: Multithreaded

<div style="font-family:'JetBrains Mono','Courier New',monospace;color:#e0e0e0">

<h1 style="text-align:center;color:#ffffff">LC2H | Lost Cities: Multithreaded</h1>

<p style="text-align:center">
  <a href="https://gravelhost.com/BlackRiftStudios"><img src="https://i.imghippo.com/files/XpJ5581voA.png"></a><br>
  <a href="https://github.com/Admany/Quantified-API"><img src="https://i.imghippo.com/files/k1781Ug.png"></a>
</p>

<hr style="border-color:#444">

## Overview

LC2H is a high-performance, multithreaded overhaul for **The Lost Cities** Minecraft mod. It moves the entire generation pipeline off the main thread, eliminating lag spikes and providing smooth city exploration.

## Technical Architecture

### Core Components

#### Async Job Pipeline
```java
// Example: Async chunk generation scheduling
SpawnSearchScheduler.scheduleAsync(cityPos, (result) -> {
    // Process results on main thread safely
    applyGeneratedChunks(result);
});
```

#### Quantified API Integration
- **Parallel Compute**: CPU-based parallel processing for spawn scanning
- **GPU Acceleration**: OpenCL compute shaders for heavy computations
- **Memory Management**: Dedicated GPU memory pools with automatic cleanup

#### Threading Model
- **Worker Pools**: Configurable thread pools for different workloads
- **Job Scheduling**: Priority-based async job execution
- **State Isolation**: Translation layers prevent thread-unsafe operations

#### Caching System
- **RAM Cache**: Fast in-memory storage with TTL
- **Disk Cache**: Persistent storage for large datasets
- **Combined Limits**: Prevents memory exhaustion across caches

### Key Classes

- `LC2H.java` - Main mod entry point and initialization
- `AsyncManager.java` - Central async job coordination
- `SpawnSearchScheduler.java` - Parallel spawn scanning with Quantified API
- `AdaptiveBatchController.java` - Dynamic batching for optimal performance
- `GPUMemoryManager.java` - GPU resource management

## Installation

### Requirements
- Minecraft 1.20.1
- Forge 47.x
- The Lost Cities mod
- Java 17+

### Steps
1. Download LC2H from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lc2h-lost-cities-multithreaded)
2. Place the JAR file in your `mods` folder
3. Launch Minecraft with Forge
4. Configure via Mods → LC2H → Config

## Configuration

Access the configuration UI in-game:
- **Mods** → **LC2H** → **Config**

### Key Settings

#### Performance
- **Async Double Block Batcher**: Enable parallel block placement
- **GPU Acceleration**: Toggle OpenCL compute shaders
- **Worker Threads**: Adjust thread pool sizes

#### Caching
- **Combined Cache Cap**: Total memory limit for all caches
- **Cache TTL**: Time-to-live for cached data
- **Eviction Policy**: LRU or size-based eviction

#### Diagnostics
- **Cache Stats Logging**: Periodic performance logging
- **Benchmark Tool**: Integrated performance scoring

## Contributing

We welcome contributions! Here's how to get involved:

### Development Setup
1. **Fork** this repository
2. **Clone** your fork: `git clone https://github.com/YOUR_USERNAME/LC2H-Lost-Cities-Multithreaded.git`
3. **Setup workspace**: Import as Gradle project in your IDE
4. **Create feature branch**: `git checkout -b feature/your-feature-name`

### Code Guidelines
- Follow existing code style (similar to Minecraft Forge conventions)
- Add Javadoc comments for public APIs
- Test changes thoroughly, especially threading-related code
- Update documentation for any user-facing changes

### Pull Request Process
1. **Test your changes** thoroughly
2. **Update documentation** if needed
3. **Create a Pull Request** with a clear description
4. **Wait for review** - we'll provide feedback and merge when ready

### Translation Contributions
- Translations are managed via [Crowdin](https://crowdin.com/)
- Join our Crowdin project to contribute translations
- Changes are automatically synced to the repository

### Bug Reports & Feature Requests
- Use [GitHub Issues](https://github.com/Admany/LC2H-Lost-Cities-Multithreaded/issues)
- Provide detailed reproduction steps for bugs
- Include performance metrics when reporting performance issues

## Performance

LC2H V3 achieves significant performance improvements:

- **Generation Speed**: 2-3x faster than vanilla Lost Cities
- **CPU Utilization**: Efficient use of multi-core systems
- **Memory Usage**: Controlled via configurable caching
- **Scalability**: Performance scales with hardware capabilities

### Benchmark Results
- **Light Modpacks**: 15k-25k performance score
- **Heavy Modpacks**: 30k-40k+ performance score
- **GPU Acceleration**: Additional 20-50% speedup on supported hardware

## Compatibility

### Supported Mods
- **C2ME** - Enhanced chunk generation
- **Sinytra Connector** - Fabric mod compatibility
- **Distant Horizons** - LOD rendering

### Known Issues
- Some mods may conflict with async generation
- GPU acceleration requires compatible hardware
- Very high city densities may still cause brief stutters

## License

This project is licensed under **BRSSLA v1.3**.

### Modpack Usage
- **CurseForge**: Automatic permission for all modpacks
- **Non-commercial**: Allowed until 100,000 total downloads
- **Commercial**: Contact BlackRift Studios for licensing
- **Credit**: Always retain proper attribution

## Credits

- **McJty** - Creator of The Lost Cities
- **BlackRift Studios** - Development and maintenance
- **Quantified API** - Parallel computing framework
- **Community Contributors** - Bug reports, testing, and translations

---

**LC2H V3.0.0** - Created by Admany - BlackRift Studios

</div>

<!-- WHAT IS -->
<p style="font-size:18px;color:#90caf9">What Is LC2H</p>

<p>
LC2H is a performance and stability rework layer for <strong>The Lost Cities</strong>.
</p>

<p>
It preserves the art, profiles, and gameplay identity of Lost Cities, but replaces the entire execution model under the hood.
</p>

<p style="color:#ffffff;font-weight:700">
V3 moves the full generator pipeline off the Minecraft main thread.
</p>

<p>
City generation no longer blocks ticks or freezes the game loop. Exploration stutter is replaced with smooth async work.
</p>

<p>
City chunks are generated asynchronously, in parallel, and only applied to Minecraft when the results are safe.
</p>

<hr style="border-color:#333">

<!-- V3 -->
<p style="font-size:18px;color:#81c784">V3.0.0 - An Engineering Marvel</p>

<p>
V3.0.0 is not a patch and not an extension of V2.
</p>

<p style="color:#c8e6c9">
It is a ground up rebuild that restructures Lost Cities into a job based, fully async pipeline.
</p>

<ul style="color:#a5d6a7">
<li>explicit job-based generator API</li>
<li>enterprise grade async scheduler</li>
<li>parallel worker pools under load</li>
<li>translation layer for thread-safe data</li>
<li>multi tier caching system</li>
<li>optional GPU acceleration via Quantified API</li>
</ul>

<p>
V3 is an engineering marvel that pushes Lost Cities into a performance tier it was never designed to reach.
</p>

<hr style="border-color:#333">

<!-- STRUCTURE CONTROL -->
<p style="font-size:18px;color:#ff7043">City Safe Structure System</p>

<p style="color:#ffccbc">
V3 introduces a structure control system built for dense city environments.
</p>

<p>
Vanilla and modded world structures are prevented from spawning inside city boundaries.
</p>

<p>
This guarantees no structures generate inside buildings, clip through streets, or float above cities.
</p>

<p>
V3 also cleans common worldgen artifacts:
</p>

<ul style="color:#ffab91">
<li>removes floating vines</li>
<li>clears bugged tall grass placements</li>
<li>prevents vegetation from spawning mid air above cities</li>
</ul>

<p>
The result is clean city skylines with zero visual corruption.
</p>

<hr style="border-color:#333">

<!-- CONFIG UI -->
<p style="font-size:18px;color:#ffd54f">Config UI - Commands Replaced</p>

<p style="color:#ffecb3">
All command based configuration has been removed in V3.
</p>

<p>
Configuration is now handled through a proper in game UI.
</p>

<p>
Path:
</p>

<ul style="color:#ffe082">
<li>Mods + LC2H + Config</li>
<li>Config button is located near the Done button</li>
</ul>

<p>
The config UI includes an integrated benchmarking tool.
When run, it produces a final performance score.
Higher scores indicate better performance.

On heavily modded packs such as ChaosZProject or ZombieCraft, a score in the 30k-40k range is considered excellent.
This means LC2H was fast enough to keep up with Minecraft's generation workload while still maintaining performance headroom.
</p>

<ul style="color:#ffe082">
<li>benchmarking tool is located at the bottom of the config screen</li>
</ul>

<p>
The UI acts as the control center for power modes, caches, diagnostics, and performance testing.
</p>

<hr style="border-color:#333">

<!-- TECH DIFF -->
<p style="font-size:18px;color:#64b5f6">V2 vs V3 - Technical Differences</p>

<p style="color:#bbdefb">
V2 focused on partial async hooks layered on top of Lost Cities.
</p>

<p>
Most heavy logic still depended on the main thread and shared unsafe state.
</p>

<p style="color:#bbdefb">
V3 fully separates Lost Cities logic from Minecraft execution flow.
</p>

<ul style="color:#90caf9">
<li>V2 reused existing generator hooks</li>
<li>V3 replaces the generator pipeline entirely</li>
<li>V2 reduced lag spikes</li>
<li>V3 eliminates them under normal load</li>
<li>V2 was limited by MC thread safety</li>
<li>V3 enforces thread isolation by design</li>
</ul>

<p>
This architectural change is what allows V3 to scale with hardware instead of fighting it.
</p>

<hr style="border-color:#333">

<!-- PERF -->
<p style="font-size:18px;color:#ce93d8">Performance Snapshot</p>

<p style="color:#e1bee7">
Higher is faster. Visual comparison only.
</p>

<pre style="color:#f3e5f5">
Speed
|", 
|",                     |-^
|",                     |-^       
|",                     |-^
|",          |-^        |-^
|",          |-^        |-^        3. LC2H V3
|", |-^      |-^        |-^
|", |-^      |-^        |-^        2. Vanilla
|",  
|",  1.     2.       3.            1. LC2H V2 (Behind Vanilla by 20%)
</pre>

<p>
V3 is designed to keep city generation ahead of vanilla, even at high density.
</p>

<hr style="border-color:#333">

<!-- WHY FAST -->
<p style="font-size:18px;color:#4dd0e1">Why V3 Feels So Fast</p>

<p>
Lost Cities compresses expensive logic into a small generation window.
</p>

<p>
V3 expands that window by executing the heavy steps in parallel async worker pools.
</p>

<ul style="color:#b2ebf2">
<li>main thread is no longer the bottleneck</li>
<li>workers stay saturated</li>
<li>caches prevent rebuild storms</li>
</ul>

<p>
Optional GPU acceleration offloads selected compute heavy paths when supported.
</p>

<hr style="border-color:#333">

<!-- TRANSLATION -->
<p style="font-size:18px;color:#ff8a65">The Translation Layer</p>

<p style="color:#ffccbc">
Lost Cities was never designed to be thread safe.
</p>

<p>
V3 extracts required data, converts it into a safe representation, and isolates worker threads from unsafe state.
</p>

<p>
This is what allows nearly all Lost Cities work to run off thread without corruption or crashes.
</p>

<hr style="border-color:#333">

<!-- STORY -->
<p style="font-size:18px;color:#90caf9">The V3 Story</p>

<p>
V3 is what I wanted to see with V1 of LC2H, or as we knew it, Lost Cities: Multithreaded.
</p>

<p>
V3 development started on September 17, 2025. The first prototypes were small, mostly reworks of V2 and V1 caching,
partial multithreading, and early experiments.
</p>

<p>
Around mid October, I came to the conclusion that V3 had to take the step I should have done with V1, but I was too scared.
</p>

<p>
That decision created Quantified API. Its purpose was to bring the tools to me, and to everyone, without having to remake
the same systems over and over per mod.
</p>

<p>
I will be honest: V3 caused immense pain at times. I cried, and I gave up more than once, because the complexity was real.
</p>

<p>
Multithreading The Lost Cities is on par with multithreading Minecraft's chunk generation. One mishandled piece of data,
and it stops working as it should.
</p>

<p>
All I want to say is that I am proud. Proud of the work, and how much I had to give up in real life to make this.
I hope this helps modpacks and others push performance forward, and leaves the old days of Lost Cities lag behind us.
</p>

<hr style="border-color:#333">

<!-- CREDITS -->
<div style="border:1px solid #616161;padding:14px;background-color:#111;color:#e0e0e0">

  <p style="font-size:18px;color:#b0bec5">Credits and Creator</p>

  <p style="color:#cfd8dc">
    Huge thanks to <strong>McJty</strong> for creating <strong>The Lost Cities</strong>.
  </p>

  <p>
    LC2H exists because Lost Cities deserved to scale to modern hardware without sacrificing stability or visual identity.
  </p>

  <p style="color:#b0bec5">
    All credit for the original concept, visuals, and gameplay design goes to McJty.
  </p>

  <hr style="border-color:#444">

  <p style="color:#90caf9">
    <strong>About the author</strong>
  </p>

  <p>
    I'm <strong>Admany</strong>, founder of <strong>BlackRift Studios</strong>.
  </p>

  <p>
    LC2H is built to take Lost Cities fully off the main thread, remove generation bottlenecks, and make large scale city exploration smooth on both servers and heavy modpacks.
  </p>

  <p style="color:#9e9e9e">
    LC2H is not affiliated with Mojang or McJty.
    This project is a performance rework layer, not a replacement in any way.
  </p>

</div>

<!-- FOOTER -->
<p style="text-align:center;color:#9e9e9e">
LC2H - Version 3.0.0 - Created by Admany - BlackRift Studios - January 4, 2026
</p>

</div>
