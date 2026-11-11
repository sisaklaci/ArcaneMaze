# Arcane Maze: Rift Hunt

**Arcane Maze: Rift Hunt** is an original, single-player fantasy maze shooter
by helmetsoft.com. It is written in plain Java Swing and renders its 3D world
with a software raycaster; no third-party libraries or game assets are needed.

## Features

- First-person movement, mouse look, magic bolts, and animated effects
- Procedurally generated dungeons with portal exits
- Five original enemies: Gloomling, Glimmerfang, Nightmaw, Riftling, and Archmage
- Enemy projectiles, temporary cloaking, teleporting, and a minimap
- Wave progression, score multipliers, extra lives, and synthesized sound effects

## Requirements

- Java 8 or later to compile and run from source
- Windows users can use `build.bat` with a JDK; `build.bat exe` creates a
  standalone application image with an embedded runtime (JDK 14 or later)

## Build and run

```bat
javac -encoding UTF-8 ArcaneMaze.java
java ArcaneMaze
```

Build a runnable JAR or a standalone Windows app image:

```bat
build.bat
build.bat exe
```

The app image is created under `dist\ArcaneMaze\ArcaneMaze.exe`.

## Controls

| Action | Control |
|---|---|
| Move | `W`, `A`, `S`, `D` |
| Turn | Mouse or `Left` / `Right` arrows |
| Cast | `Space` or mouse click |
| Pause / resume | `P` or `Esc` |
| Start / restart | `Enter` |

## License

The source code and procedural artwork in this repository are provided under
the MIT License. See [LICENSE](LICENSE).
