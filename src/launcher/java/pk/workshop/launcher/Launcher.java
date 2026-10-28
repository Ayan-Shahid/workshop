package pk.workshop.launcher;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Starts Workshop Manager, installing a downloaded update first.
 *
 * The "app" folder next to this jar holds:
 *   lib/                 the program's jar files; each name includes part of its checksum, so versions never clash
 *   current.txt          the jars (in lib/) that make up the version that runs
 *   previous.txt         the jars of the version before the last update, for going back
 *   update/lib/          newly downloaded jars, already checked by the app
 *   update/classpath.txt the jar list of the downloaded version
 *   update/ready         written last by the app: the downloaded version's number
 *   update/trial         a new version that hasn't yet shown it starts: "version attempts"
 *   update/failed        versions that wouldn't start, so they aren't downloaded again
 *
 * Kept small and in plain Java. Updates never replace it, so it must work with every future version.
 */
public final class Launcher {
    static final String MAIN = "pk.workshop.MainKt";
    /** Starts of a new version that never reached a working window before going back to the previous version. */
    static final int MAX_TRIES = 3;

    public static void main(String[] args) throws Exception {
        Path self = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path dir = self.getParent();
        // "--wait <pid>": a restart for an update; wait for the old window to close and let go of its files
        if (args.length >= 2 && args[0].equals("--wait")) {
            try {
                Optional<ProcessHandle> old = ProcessHandle.of(Long.parseLong(args[1]));
                if (old.isPresent()) old.get().onExit().get(60, TimeUnit.SECONDS);
            } catch (Exception ignored) { }
        }
        try { prepare(dir); } catch (Exception e) { log(dir, "update step failed: " + e); }

        List<URL> urls = new ArrayList<>();
        for (String name : lines(dir.resolve("current.txt"))) urls.add(dir.resolve("lib").resolve(name).toUri().toURL());
        if (urls.isEmpty()) urls.add(dir.resolve("WorkshopManager.jar").toUri().toURL());
        System.setProperty("workshop.launcher", self.toString());
        System.setProperty("workshop.appDir", dir.toString());
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), Launcher.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        Method m = cl.loadClass(MAIN).getMethod("main", String[].class);
        m.invoke(null, (Object) new String[0]);
    }

    /** Installs a waiting update, or goes back to the previous version if the new one keeps failing to start. */
    static void prepare(Path dir) throws IOException {
        Path up = dir.resolve("update"), lib = dir.resolve("lib");
        Path ready = up.resolve("ready"), newCp = up.resolve("classpath.txt"), trial = up.resolve("trial");
        Path current = dir.resolve("current.txt"), previous = dir.resolve("previous.txt");

        if (Files.exists(ready) && Files.exists(newCp)) {
            String version = read(ready);
            List<String> want = lines(newCp);
            Files.createDirectories(lib);
            for (String name : want) {
                Path staged = up.resolve("lib").resolve(name), dest = lib.resolve(name);
                if (Files.exists(staged)) Files.move(staged, dest, StandardCopyOption.REPLACE_EXISTING);
                if (!Files.exists(dest)) { log(dir, "update " + version + " is missing " + name + "; not installed"); Files.delete(ready); return; }
            }
            if (Files.exists(current)) Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING);
            Path tmp = dir.resolve("current.txt.new");
            Files.copy(newCp, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, current, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(ready);
            Files.deleteIfExists(newCp);
            write(trial, version + " 0");
            log(dir, "installed update " + version);
            prune(dir);
            return;
        }

        if (Files.exists(trial)) {
            String[] t = read(trial).split(" ");
            int tries = t.length > 1 ? Integer.parseInt(t[1]) : 0;
            if (tries >= MAX_TRIES && Files.exists(previous)) {
                Files.copy(previous, current, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(trial);
                Files.writeString(up.resolve("failed"), t[0] + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log(dir, "version " + t[0] + " did not start properly; went back to the previous version");
            } else {
                write(trial, t[0] + " " + (tries + 1));
            }
        }
    }

    /** Deletes jars that neither the current nor the previous version uses. */
    static void prune(Path dir) {
        try {
            Set<String> keep = new HashSet<>(lines(dir.resolve("current.txt")));
            keep.addAll(lines(dir.resolve("previous.txt")));
            try (Stream<Path> s = Files.list(dir.resolve("lib"))) {
                s.filter(p -> !keep.contains(p.getFileName().toString())).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
            Path staged = dir.resolve("update").resolve("lib");
            if (Files.isDirectory(staged)) try (Stream<Path> s = Files.list(staged)) {
                s.forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        } catch (IOException ignored) { }
    }

    static List<String> lines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l.trim());
        return out;
    }

    static String read(Path p) throws IOException { return Files.readString(p, StandardCharsets.UTF_8).trim(); }

    static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    static void log(Path dir, String line) {
        try {
            Path f = dir.resolve("update").resolve("update.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f, java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ') + "  " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    private Launcher() { }
}
