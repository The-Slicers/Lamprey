# Lamprey

Lamprey is a 3D-printing slicer written in [Quorum](https://quorumlanguage.com), an accessible programming language. It turns a 3D model into a file a printer can run, and it is built so that blind and low-vision makers can slice and print without depending on software their screen reader can't use.

The Mountain Lakes Public Library makerspace in New Jersey asked for Lamprey: a slicer the library can own, read and extend, so every maker can print on their own. Lamprey is a senior design project at UNLV, advised by Professor Andreas Stefik.

## Why another slicer?

A 2025 study tested Cura, PrusaSlicer and Bambu Studio with the NVDA screen reader and found that core tasks were blocked by controls the screen reader could not detect ([Ballarin et al., DIS '25](https://doi.org/10.1145/3715668.3736342)). In Bambu Studio, pressing Tab starts slicing instead of moving to the next control.

Lamprey avoids those barriers:

- **No positioning by sight.** Lamprey turns each model so the side with the most flat area sits on the plate.
- **Text output.** Progress and warnings print as plain text that a screen reader reads aloud.
- **One settings file.** Every printer, filament and print setting lives in `Profiles/profiles.json`.

## Supported printers

| Profile | Printer | Output |
|---|---|---|
| `bambu-p2s` | Bambu Lab P2S, PLA | G-code and `.3mf` |
| `bambu-p2s-petg` | Bambu Lab P2S, PETG | G-code and `.3mf` |
| `bambu-a1` | Bambu Lab A1, PLA (needs physical test prints) | G-code and `.3mf` |
| `ender3-v2` | Creality Ender 3 V2 | G-code |
| `ender3-v2-fine` | Creality Ender 3 V2, 0.12 mm layers | G-code |

## How it slices

```
model -> TriangleMesh -> MeshSlicer -> ContourGenerator -> PathGenerator
      -> TreeSupport, SkirtGenerator -> PathOrderer -> GcodeWriter -> BambuPackager
```

1. **Load the model:** STL or OBJ, read with Quorum's `ModelLoader`.
2. **Cut into layers:** turn the model so its flattest side is on the plate, then slice every layer plane (`MeshSlicer`).
3. **Find the outlines:** chain segments into closed loops and bridge gaps in damaged meshes (`ContourGenerator`).
4. **Plan the toolpaths:** walls, solid top and bottom skin, and zigzag infill (`PathGenerator`).
5. **Add supports and skirt:** tree supports grown from the plate, plus a skirt or brim (`TreeSupport`, `SkirtGenerator`).
6. **Order the moves:** a nearest-neighbor tour of each layer (`PathOrderer`).
7. **Write the print file:** G-code with each printer's start and end codes, plus a `.3mf` project for Bambu printers (`GcodeWriter`, `BambuPackager`).

## Getting started

You need [Quorum Studio](https://quorumlanguage.com) (the project was built with Quorum Studio 8.0.3).

1. Clone the repo and open `Project/Project.qp` in Quorum Studio.
2. Put a model in `assets/`. Model files are not checked in, so add your own `.stl` or `.obj`.
3. In `SourceCode/Main.quorum`, set the four values at the top of the `Main` class:

   ```
   text modelPath = "assets/your-model.stl"
   text profilesPath = "Profiles/profiles.json"
   text profileName = "bambu-p2s"
   text outputFolder = "Outputs"
   ```

4. Run the project. Lamprey prints its progress and any warnings, and writes the print file to `Outputs/`.

You can change one setting for a single run without editing the profile file:

```
profile:SetNumber("layerHeight", 0.16)
```

## Profiles

`Profiles/profiles.json` holds every profile. A profile lists only the settings it changes. `inherits` names the profile it starts from, and anything not set falls back to the defaults in `ProfileSchema.quorum`. Keys that start with `_` are comments.

To add a printer, copy the closest existing profile, set `inherits`, and add start and end G-code under `Templates/<printer>/`.

## Tests

- **Quorum tests:** set `runTests = true` in `SourceCode/Main.quorum` and run the project. The suite covers geometry, slicing, profiles, G-code output and end-to-end slice jobs.
- **Java plugin tests:** run `sh Plugins/test.sh`. Run `sh Plugins/build.sh` to rebuild `Plugins/lamprey-plugins.jar` after changing the Java source.

## Project layout

```
SourceCode/Main.quorum            entry point: pick a model and a profile, then slice
SourceCode/Libraries/Fabrication  slicer: geometry, slicing, profiles, G-code, Bambu packaging
SourceCode/Libraries/Data         number formatting, JSON templates, zip writing for .3mf
SourceCode/Tests                  Quorum test suite
Profiles/profiles.json            printer, filament and print settings
Templates/                        per-printer start and end G-code and Bambu project settings
Plugins/                          small Java plugin (zip writing) and its build and test scripts
UMLDiagram/                       pipeline diagram (PlantUML)
Outputs/                          sliced files are written here
```

## Roadmap

- A keyboard-only, screen-reader-friendly menu with spoken progress and warnings
- Faster slicing through parallel geometry work and fewer memory allocations
- Physical test prints for the Bambu A1 profile
- Less stringing on organic prints
- Usability testing with blind makers

## Team

Benjamin Gregorich, Lucas Andersen, Marisa Kirchner, Britney Gonzalez Hernandez, James Li and Aiden Yamasta. Advisor: Professor Andreas Stefik.

## Acknowledgements

Thanks to Ian Matty and Richard He from the Mountain Lakes Public Library makerspace, who asked for this project, and Dr. Yingtao from the UNLV AEB Makerspace for printer access.

## References

1. A. Ballarin, A. Stangl, S. K. Oswal, E. Whiting. "A Framework-Informed Analysis of Accessibility Barriers in Desktop 3D Printing Software." *DIS '25 Companion*, ACM, 2025. [doi.org/10.1145/3715668.3736342](https://doi.org/10.1145/3715668.3736342)
2. E. Brulé, G. Bailly. "'Beyond 3D Printers': Understanding Long-Term Digital Fabrication Practices for the Education of Visually Impaired or Blind Youth." *CHI '21*, ACM, 2021. [doi.org/10.1145/3411764.3445403](https://doi.org/10.1145/3411764.3445403)

## License

BSD 3-Clause. See [LICENSE](LICENSE).
