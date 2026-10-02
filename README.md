# ReJar

ReJar is a desktop tool (JavaFX 21.0.12) for decompiling jar files and patching the classes inside them.

- **Browse.** Each jar opens in its own tab. The tree shows packages, classes (nested classes are grouped with their outer class) and resources. You can filter the tree, drag and drop jars onto the window, and reopen recent files.
- **Decompile.** Classes are decompiled on demand with [Vineflower](https://github.com/Vineflower/vineflower) and shown with syntax colors. A bytecode view (ASM listing) is also available. Ctrl+click a type name to open it, and Ctrl+N opens a class by name (camel-case initials work).
- **Resources.** Text resources are shown with syntax colors (XML, JSON, properties, YAML, manifest) and can be edited. Images are previewed. Other binary files are shown as a hex dump. You can also extract an entry, replace it, add files or delete entries.
- **Search.** You can search the decompiled sources, the text resources and the entry names. Options: case-sensitive, whole word, regex. Results are grouped by file; double-click one to open it at the matching line. In-file find is Ctrl+F / F3 and go-to-line is Ctrl+G.
- **Edit and recompile.** Click *Edit* (Ctrl+E), change the source, then click *Compile & Apply* (Ctrl+S). `javac` compiles the source in memory. Its `--release` is taken from the original class file version. Compiler errors are listed and clickable. The new class files replace the old ones, and nested classes that no longer exist are removed. *Compare* shows the originally decompiled source next to the edited one.
- **Save to a new jar.** *Save as New JAR* (Ctrl+Shift+S) never overwrites the opened jar. It keeps the entry order and the compression method of each entry (so STORED nested jars in Spring Boot stay valid). If the jar was signed, the signature is removed.
- **Source jar.** *Create Source JAR* decompiles every class into `<name>-sources.jar` (`.java` files under their package path, pending edits included), ready to attach as sources in an IDE.
- **History and revert.** The new jar records every change set under `META-INF/rejar/`:
  ```
  META-INF/rejar/history.json                          change sets: id, date, user, description, entries, sha-256
  META-INF/rejar/changes/<id>/original/<entry>.bin     original binaries (to revert)
  META-INF/rejar/changes/<id>/modified/<entry>.bin     new binaries
  META-INF/rejar/changes/<id>/src/<unit>.java          edited source that was compiled
  META-INF/rejar/changes/<id>/src-original/<unit>.java source as decompiled before editing
  ```
  Stored class files get a `.bin` suffix so class-path scanners ignore them. Open a patched jar and go to *Changes & History*. There you can revert a whole change set or just some of its entries. The reverted state is written to another new jar and recorded as a change set of its own. For a class that was already patched, *Load Stored Source* restores the edited source (with its comments and formatting) instead of decompiling it again.

## Compilation classpath

The classpath is built in this order (see *Classpath…* on the jar tab):

1. Pending changes, so classes you already edited are visible to other edits.
2. The jar content. `BOOT-INF/classes` and `WEB-INF/classes` are extracted automatically.
3. Jars nested inside the jar (`BOOT-INF/lib`, `WEB-INF/lib`, …). This can be turned off.
4. **Maven dependencies.** ReJar runs `mvn dependency:build-classpath` (compile and provided scopes) on the `META-INF/maven/**/pom.xml` embedded in the jar, or on an external `pom.xml` you choose. This happens automatically the first time you compile.
5. **Custom jars and class folders** that you add.

Per-jar settings are remembered: custom entries, `--release` override, extra javac options (for example `--add-exports …`). In *Tools › Settings* you can set the Maven executable (otherwise it is found through `MAVEN_HOME`/`PATH`), extra Maven arguments (for example `-o` or `-s settings.xml`), the font size and a dark theme.

## Build and run

Requires JDK 21 (a JDK, not a JRE: ReJar uses the system Java compiler) and Maven 3.9+.

```bash
mvn package
```

```bash
java -jar target/rejar-1.0.0-SNAPSHOT-all.jar [some.jar ...]
```

or, during development:

```bash
mvn javafx:run
```

The `-all` jar contains the JavaFX native libraries for the platform it was built on (here Windows). Build it on each target OS, or use `mvn javafx:run`.

### Native executable (no Java installation needed)

`jpackage` from the JDK builds a native launcher with its own Java runtime. The runtime is trimmed to Java SE plus `jdk.compiler`, so editing and recompiling still work. Build on the OS you are targeting.

Portable app: `target/dist/ReJar/ReJar.exe` (Windows) or `ReJar.app` / `ReJar/bin/ReJar` (macOS / Linux), zipped as `target/dist/ReJar-<version>-<os>.zip`, about 50 MB:

```bash
mvn package -Pnative
```

Installer: `.exe` on Windows, `.deb` on Linux, `.dmg` on macOS. The Windows installer adds a Start-menu entry and a desktop shortcut, and lets you choose the install folder:

```bash
mvn package -Pinstaller
```

To pick another format, add `-Dinstaller.type=msi` (Windows), `rpm` (Linux) or `pkg` (macOS).

Installers have extra requirements. On Windows you need the [WiX Toolset](https://wixtoolset.org/) 3.x on the `PATH`, since JDK 21's `jpackage` uses WiX 3. On Linux you need `dpkg-deb` for `.deb` or `rpm-build` for `.rpm`. The portable app has no extra requirements.

Resolving dependencies with Maven still calls an installed `mvn` (see *Tools › Settings*), and that `mvn` needs its own Java.

The packaging icons in `src/packaging/` (`.ico`, `.icns`, `.png`) are generated with `java tools/IconGen.java src/main/resources/io/rejar/ui/icons src/packaging`.

## Limitations

- Decompiled code does not always recompile as-is (a known limit of every decompiler). When that happens, fix the reported errors in the editor.
- A jar nested inside a jar (`BOOT-INF/lib/x.jar`) opens in its own tab. Patch it there, save it as a new jar, then put it back in the outer jar with *Replace with File…*.
- For multi-release jars, `META-INF/versions/N/` classes are shown and edited as separate units.

## Project layout

```
io.rejar.core   JarModel (original entries plus pending overlay), DecompilerService (Vineflower),
                SourceCompiler (in-memory javac), ClasspathBuilder / MavenResolver, JarWriter,
                History / HistoryStore, SearchService, TextSupport
io.rejar.ui     MainWindow, JarTab, EntryTree, ClassEditorTab, ResourceTab, CodeEditor + Highlighter,
                SearchPane, ChangesPane, CompareTab and dialogs
tools/IconGen   draws the application icon (src/main/resources/io/rejar/ui/icons/rejar-<size>.png)
                and the jpackage icons (src/packaging/rejar.ico|icns|png)
src/packaging   jpackage icons and the assembly descriptor zipping the portable app
```
