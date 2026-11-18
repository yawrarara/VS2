<p align="center">
<img src="vs_logo.png" width="200" height="200">
</p>
<h1 align="center">
Valkyrien Skies 2 — Unofficial Fabric Port
</h1>
<p align="center">
<a href="https://www.valkyrienskies.org/"><img src="https://img.shields.io/badge/Website-white?style=for-the-badge&logo=html5&logoColor=black" alt="Website"></a>
<a href="https://www.curseforge.com/minecraft/mc-mods/valkyrien-skies"><img src="https://img.shields.io/badge/CurseForge-white?style=for-the-badge&logo=curseforge" alt="CurseForge"></a>
<a href="https://modrinth.com/mod/valkyrien-skies"><img src="https://img.shields.io/badge/Modrinth-white?style=for-the-badge&logo=modrinth" alt="Modrinth"></a>
<a href="https://wiki.valkyrienskies.org/wiki/Main_Page"><img src="https://img.shields.io/badge/Wiki-white?style=for-the-badge&logo=wikipedia&logoColor=black" alt="Wiki"></a>
<a href="https://discord.gg/rG3QNDV"><img src="https://img.shields.io/badge/Discord-white?style=for-the-badge&logo=discord" alt="Discord"></a>
</p>

> ⚠️ **Unofficial port — not affiliated with or endorsed by the Valkyrien Skies team.** A community **Fabric** port of VS2 to newer Minecraft versions. It may be less polished than official VS — use at your own risk, no official support. The badges above link to the **official** Valkyrien Skies works; please support the team there.

*The physics mod to end all other physics mods. Better compatibility, performance, collisions, interactions and physics than anything prior!*

![Valkyrien Skies showcase](https://user-images.githubusercontent.com/26909616/199406363-38e1d032-9c18-4aef-a74a-23f4b268e6ad.png)

## About this port

This is a **Fabric-only** port of Valkyrien Skies 2 to **Minecraft 1.20.1, 1.21.1, 1.21.11, and 26.1.2**, focused on getting Eureka ships fully playable on current versions — including under shaders. Everything from official VS2 that could be carried over is here, plus a set of port-specific rendering, physics, and quality-of-life additions.

Pair it with the matching **[Eureka! Ships! (VS2)](https://github.com/Eminai-LeoVinci/Eureka-Ships)** build to place a helm and turn any build into a sailable ship.

## Highlights

- **Ships render under Sodium + Iris shaders** — with shadows, emissive / PBR blocks, and swaying foliage; large fleets stay smooth.
- **Ocean bob & sway** — ships heave and roll on the swells with the pilot riding along (VS2-native, no Physics Mod).
- **Ride anywhere** — relog on a moving ship safely, keep the ship's influence when you jump off, a sit-down hotkey, an F5 ship-camera cycle, and controller steering.
- **Living decks** — mobs, villagers, and armor stands path and stay synced on moving ships.

See the [**Releases**](https://github.com/Eminai-LeoVinci/VS2/releases) page for the complete, current feature list.

## 26.1.2 build

The **26.1.2** branch of this port is the one used by the [Trackwork](https://github.com/Eminai-LeoVinci/trackwork-port) Fabric port, and it carries the Create compatibility fixes that make Create contraptions usable on ships:

- **Create super glue on ships** — glue placement, removal and reach checks resolve through the ship↔world transform, and existing glue boxes are outlined in ship space.
- **Create seats on ships** — sitting on a seat that is part of a ship mounts the player on the ship instead of falling through.

Ships built with Create Fly (`com.zurrtum.create`, 6.0.9) and Trackwork rely on these; plain upstream VS2 does not have them.

## Installation

1. Install **Fabric Loader** + **Fabric API**, and **Sodium** (required — ship terrain won't render without it).
2. Download the jar for your Minecraft version from [**Releases**](https://github.com/Eminai-LeoVinci/VS2/releases) or Modrinth.
3. Add the matching **Eureka (VS2)** build for ship building.

*Official Valkyrien Skies (other versions / loaders) is available from the [official website](https://www.valkyrienskies.org/download).*

> VS2's internal debug / test items are **excluded** from this build.

## Building from source

Requires **JDK 25** (the Gradle wrapper pins the Gradle version). The build compiles against a handful of other client mods, but **none of them are bundled into the jar** — they are compile-time only.

```sh
# 1. Collect the compile-only mod jars into one directory.
#    Names must match the `localMods` list in build.gradle:
#    cloth-config, ForgeConfigAPIPort, Sodium, Voxy, Iris, EMF, ETF, Create Fly
mkdir -p ~/vs-mods   # put the jars here

# 2. Build
./gradlew build -PlocalMods="$HOME/vs-mods"
```

Output: `build/libs/valkyrienskies-26-1-2-2.4.205+mc26.1.2.jar`

CI runs the same build on every push: [`.github/workflows/build.yml`](.github/workflows/build.yml) downloads those jar files from their public Modrinth CDN URLs, builds on JDK 25, uploads the artifact, and attaches the jar to a GitHub release when a `v*` tag is pushed. A locally built jar can have a different SHA-256 than the CI one, because text resources keep the line endings of the checkout (CRLF on Windows, LF on the runner); the compiled classes are identical.

## Credits & License

Valkyrien Skies 2 was originally created by **Triode** and **Rubydesic**, with contributions from the wider VS team (see the upstream git history). The Create compatibility code was originally and largely written by [FluffyJenkins](https://github.com/FluffyJenkins/). This unofficial port is maintained by **Eminai-LeoVinci** and is not affiliated with the Valkyrien Skies team.

Released under the **GNU GPL v3**, in keeping with the upstream project and the VS team's guidance for community ports.
