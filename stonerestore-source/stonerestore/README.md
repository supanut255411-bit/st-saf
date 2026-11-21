# StoneRestore

Client-side Fabric mod for **Minecraft Java 1.21.11** (Java 21) that restores vanilla textures while an
"every block is stone" resource pack (Unstable SMP style) **stays enabled**.

## How it works

The pack is never disabled, edited or deleted. On every resource reload the mod:

1. **Wraps each enabled pack** (`RestoringResourcePack`) in the one place where the final pack list is known
   (`ReloadableResourceManagerImpl.reload`, one mixin). Pack order therefore cannot defeat it.
2. **Scans lazily** on the resource-loader threads (not the render thread). A texture is a "stone replacement"
   when its bytes - or, for re-encoded files, its decoded pixels - equal vanilla `stone.png`, while vanilla's
   own texture at that path is something else. `stone.png` itself, paths vanilla doesn't have, and packs with
   fewer than `minReplacements` (default 8) such textures are left alone.
3. **Hides those textures inside the pack** (plus their `.png.mcmeta`). Lookups fall through to the next pack
   down, ending at the vanilla assets that Minecraft always has loaded. Models, blockstates, tinting, atlas
   stitching, animation and everything else stay untouched because only the file lookup changes.
   *Preserve Other Resource Packs = OFF*: the stone pack serves the vanilla file itself instead, which also
   beats lower packs.
4. **Verifies** after the reload: each restored identifier is looked up in the real resource manager; anything
   that still resolves to stone is logged with its exact identifier and is *not* counted as restored.

The chat report (`347 analyzed / 329 restored`) uses those real numbers. Vanilla textures are read from the
player's own client at runtime, so nothing from Mojang is bundled and the version always matches.

## Build

Needs JDK 21.

1. Open `gradle.properties` and confirm the five values marked `CONFIRM` against https://fabricmc.net/develop/
   (version 1.21.11). They could not be checked offline when this was written.
2. Create the wrapper once, using the Gradle version the Fabric template generator gives for 1.21.11:
   `gradle wrapper --gradle-version <that version>` (or copy `gradlew`, `gradlew.bat`, `gradle/wrapper/` from a
   generated Fabric template).
3. `./gradlew build` -> `build/libs/stonerestore-1.0.0.jar`. Put it in `mods/` next to Fabric API.

If a compile error appears, it is almost certainly one of the 1.21.11 API spots listed under *Not yet verified*.

## Use

| Command | Effect |
|---|---|
| `/stonerestore on` / `off` | enable / disable (reloads only if the state changed) |
| `/stonerestore scan` | report which enabled packs are stone packs (no changes) |
| `/stonerestore reload` | drop cached scans and reload textures |
| `/stonerestore status` | settings + result of the last verified reload |
| `/stonerestore force [off]` | skip detection: restore every texture of targeted packs that has a vanilla counterpart (also overrides legitimate custom textures in those packs) |
| `/stonerestore reset` | defaults |
| `/stonerestore config` | config screen (reloads on close only if something relevant changed) |
| `/stonerestore exclude <pack id>` | toggle a pack on the never-touch list (ids are shown by `scan`) |
| `/stonerestore maketestpack` | writes the two test packs into `resourcepacks/` |

Config file: `config/stonerestore.json` (also `targetPacks`, `excludedPacks`, `minReplacements`).
Scans of normal pack files are cached by name + size + modified time + settings, so F3+T and pack
re-ordering don't rescan unchanged packs. Directory packs and server packs are rescanned each reload.

## Test plan (maps to the 7 required tests)

Run `/stonerestore maketestpack` once, then enable the packs from the resource pack screen.

1. **Normal Minecraft** - no packs: nothing happens, no chat message.
2. **Stone pack enabled** - `StoneRestore-TestPack-AllStone.zip`: every block looks vanilla; chat shows the report.
3. **Stone + custom pack** - also enable `StoneRestore-TestPack-Custom.zip` *below* the stone pack: diamond block
   is magenta, gold block cyan (preserved), everything else vanilla. With *Preserve Other Packs* OFF they turn vanilla.
4. **F3+T** - report appears again; the second scan comes from the cache.
5. **Pack order** - drag the packs around: result stays correct.
6. **Unknown assets** - add any modded/new texture files to a copy of the pack: counted as "unknown", never touched.
7. **Performance** - scan is a single pass over the pack's PNGs (small reads, no decoding unless the size
   matches stone); no per-frame hooks exist.

## Verified vs not verified

Verified in the build sandbox (JDK 21, no network):

* The detection core (`dev.stonerestore.core`, no Minecraft imports) compiles and passes `tools/CoreSelfTest.java`
  (28 checks: PNG decoding cross-checked against ImageIO for RGBA/RGB/palette/gray, byte-copy and re-encoded stone
  detection, legit stone left alone, threshold, categories, force mode, unknown paths, oversize files).
  Run it: `javac -d out src/main/java/dev/stonerestore/core/*.java tools/CoreSelfTest.java && java -cp out CoreSelfTest`
* The Minecraft-facing code compiles against hand-written API *stubs* - this checks my own syntax and types only.

**Not yet verified** (needs a real Gradle/Loom build and an in-game run; no JAR has been produced):

* Yarn names/signatures for 1.21.11, most likely to need a tweak: `ResourcePack` abstract methods
  (`openRoot/open/findResources/getNamespaces/parseMetadata/getInfo/close`), `ReloadableResourceManagerImpl#reload`
  parameters + its `type` field (mixin), `ResourceReload#whenComplete`, `ResourcePackProfile#createResourcePack/getId`,
  `ResourcePackManager#getProfile/getEnabledProfiles`, `Resource#getInputStream`, `DrawContext#drawCenteredTextWithShadow`,
  `MinecraftClient#send/reloadResources`, and the Fabric API event/command class names.
  Use `./gradlew genSources` to read the real signatures.
* Mixin behaviour at runtime, the pack format number in the generated test packs, Sodium compatibility.
* Not handled: a pack that makes blocks look like stone by overriding **model JSON** to point at `block/stone`
  instead of replacing PNGs. Only texture files are restored.
