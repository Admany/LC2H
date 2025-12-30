<div style="font-family:'JetBrains Mono','Courier New',monospace;color:#e0e0e0">

<h1 style="text-align:center;color:#ffffff">《▓ LC²H | Lost Cities Hybrid ▓》</h1>

<p style="text-align:center">
  <a href="https://gravelhost.com/BlackRiftStudios"><img src="https://i.imghippo.com/files/XpJ5581voA.png"></a><br>
  <a href="https://github.com/Admany/Quantified-API"><img src="https://i.imghippo.com/files/k1781Ug.png"></a>
</p>

<hr style="border-color:#444">

<!-- COMPAT NOTICE -->
<div style="border:1px solid #ef5350;color:#ffcdd2;padding:10px">
  <p style="font-size:18px;color:#ffebee">《▒ Compatibility Notice ▒》</p>
  <p>
    <strong>C2ME</strong> has <strong>not been tested</strong> on <strong>V3.0.0</strong> yet.<br>
    - Full support is planned/being tested<br>
    - Treat as experimental for now (expect errors, crashes)
  </p>
</div>

<br>


<hr style="border-color:#333">

<div style="border:1px solid #ffd54f;padding:12px;background-color:#222;color:#fff">

  <p style="font-size:16px;color:#ffeb3b"><strong>《▒ New License Recap - Modpack Usage ▒》</strong></p>

  <p style="color:#fff9c4">
    The Software is now under a <strong>new license (BRSSLA v1.3)</strong> with updated terms. 
    Modpack usage rules have changed. Please read carefully!!!
  </p>

  <ul style="color:#fff9c4">
    <li>Any modpack hosted on <a href="https://curseforge.com" style="color:#ffe082">CurseForge</a> is automatically allowed to include the Software.</li>
    <li>Non-commercial usage is allowed until total downloads exceed <strong>100,000</strong> globally.</li>
    <li>Above 100,000 downloads, the modpack is considered <strong>commercial</strong> and must request a license from BlackRift Studios.</li>
    <li>Non-compliant modpacks can be blacklisted and/or have the Software disabled.</li>
    <li>Always retain proper credit and do not bypass licensing enforcement.</li>
  </ul>

  <p style="color:#ffe082">
    <strong>Unaffected modpacks:</strong> Cursed Walking, DeceasedCraft, ZombieCraft
  </p>

  <p style="color:#ffe082">
    TLDR: <strong>CurseForge modpacks under 100k downloads = free to use. Above that = contact us for a commercial license.</strong>
  </p>

</div>

<!-- WHAT IS -->
<p style="font-size:18px;color:#90caf9">《▒ What Is LC²H ▒》</p>

<p>
LC²H is a performance and stability rework layer for <strong>The Lost Cities</strong>.
</p>

<p>
It keeps the visuals and identity of Lost Cities, but completely replaces how the generator executes internally.
</p>

<p style="color:#ffffff;font-weight:700">
V3 moves the entire Lost Cities generation pipeline off the Minecraft main thread.
</p>

<p>
This means city generation no longer blocks ticks, no longer freezes the game loop, and no longer causes exploration stutter.
</p>

<p>
City chunks are generated asynchronously, in parallel, and fed back safely to Minecraft only when ready.
</p>

<hr style="border-color:#333">

<!-- V3 -->
<p style="font-size:18px;color:#81c784">《▒ V3.0.0 - A Real Rework ▒》</p>

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
V3 pushes Lost Cities into a performance tier it was never designed to reach.
</p>

<hr style="border-color:#333">

<!-- STRUCTURE CONTROL -->
<p style="font-size:18px;color:#ff7043">《▒ City Safe Structure System ▒》</p>

<p style="color:#ffccbc">
V3 introduces a new structure control system designed specifically for dense city environments.
</p>

<p>
All vanilla and modded world structures such as villages, ruined nether portals, and other random structure features are actively prevented from spawning inside city boundaries.
</p>

<p>
This guarantees that no structures generate inside buildings, clip through streets, or float above cities.
</p>

<p>
In addition, V3 automatically cleans up common worldgen artifacts.
</p>

<ul style="color:#ffab91">
<li>removes floating vines</li>
<li>clears bugged tall grass placements</li>
<li>prevents vegetation from spawning mid air above cities</li>
</ul>

<p>
The result is fully clean city skylines with zero visual corruption.
</p>

<hr style="border-color:#333">

<!-- CONFIG UI -->
<p style="font-size:18px;color:#ffd54f">《▒ Config UI - Commands Replaced ▒》</p>

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
<li>Mods → LC²H → Config</li>
<li>Config button is located near the Done button</li>
</ul>

<p>
The config UI includes an integrated benchmarking tool.
When run, it produces a final performance score.
Higher scores indicate better performance.

On heavily modded packs such as ChaosZProject or ZombieCraft, a score in the 30k–40k range is considered excellent.
This means LC²H was fast enough to keep up with Minecraft’s generation workload while still maintaining performance headroom.
</p>

<ul style="color:#ffe082">
<li>benchmarking tool is located at the bottom of the config screen</li>
</ul>

<p>
The UI acts as the control center for power modes, caches, diagnostics, and performance testing.
</p>

<hr style="border-color:#333">

<!-- TECH DIFF -->
<p style="font-size:18px;color:#64b5f6">《▒ V2 vs V3 - Technical Differences ▒》</p>

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
<p style="font-size:18px;color:#ce93d8">《▒ Performance Snapshot ▒》</p>

<p style="color:#e1bee7">
Higher is faster. Visual comparison only.
</p>

<pre style="color:#f3e5f5">
Speed
│
│                 █
│                 █       
│                 █
│        █        █
│        █        █        3. LC²H V3
│ █      █        █
│ █      █        █        2. Vanilla
│  
│  1.     2.       3.      1. LC²H V2 (Behind Vanilla by 20%)
└──────────────────────────────
</pre>

<p>
V3 is designed to keep city generation ahead of vanilla, even at high density.
</p>

<hr style="border-color:#333">

<!-- WHY FAST -->
<p style="font-size:18px;color:#4dd0e1">《▒ Why V3 Feels So Fast ▒》</p>

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
<p style="font-size:18px;color:#ff8a65">《▒ The Translation Layer ▒》</p>

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

<!-- CREDITS -->
<div style="border:1px solid #616161;padding:14px;background-color:#111;color:#e0e0e0">

  <p style="font-size:18px;color:#b0bec5">《▒ Credits & Creator ▒》</p>

  <p style="color:#cfd8dc">
    Huge thanks to <strong>McJty</strong> for creating <strong>The Lost Cities</strong>.
  </p>

  <p>
    LC²H exists because Lost Cities deserved to scale to modern hardware without sacrificing stability or visual identity.
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
    LC²H is built to take Lost Cities fully off the main thread, remove generation bottlenecks, and make large scale city exploration smooth on both servers and heavy modpacks.
  </p>

  <p style="color:#9e9e9e">
    LC²H is not affiliated with Mojang or McJty.  
    This project is a performance rework layer, not a replacement in any way!
  </p>

</div>

<!-- FOOTER -->
<p style="text-align:center;color:#9e9e9e">
LC²H - Version 3.0.0 - Created by Admany - BlackRift Studios - December 21, 2025
</p>

</div>
