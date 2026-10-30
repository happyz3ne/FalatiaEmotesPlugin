package org.falatia.emotes.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ServerEmotes {

    record EmoteFile(String path, byte[] packed) {
    }

    private static final int MAX_DEPTH = 4;
    private static final int MAX_PACKED = 1_000_000;
    private static final int MAX_PATH = 256;
    static final int MAX_FILES = 1024;
    private static final int MAX_FILE = 8 * 1024 * 1024;

    private final FalatiaEmotesPlugin plugin;
    private final Path dir;
    private List<EmoteFile> files = List.of();

    ServerEmotes(FalatiaEmotesPlugin plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("emotes");
    }

    List<EmoteFile> files() {
        return this.files;
    }

    void load() {
        List<EmoteFile> loaded = new ArrayList<>();
        try {
            Files.createDirectories(this.dir);
            List<Path> paths;
            try (Stream<Path> walk = Files.walk(this.dir, MAX_DEPTH)) {
                paths = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path path : paths) {
                String name = this.dir.relativize(path).toString().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip")) {
                    loadArchive(path, name, loaded);
                } else if (relevant(lower)) {
                    add(name, Files.readAllBytes(path), loaded);
                }
            }
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Емоції: не вдалося прочитати " + this.dir, e);
        }
        if (loaded.size() > MAX_FILES) {
            this.plugin.getLogger().warning("Емоції: у " + this.dir + " більше " + MAX_FILES + " файлів, решту мод не зможе отримати");
            loaded = new ArrayList<>(loaded.subList(0, MAX_FILES));
        }
        this.files = List.copyOf(loaded);
        if (!this.files.isEmpty()) {
            this.plugin.getLogger().info("Емоції: серверних файлів - " + this.files.size());
        }
    }

    private void loadArchive(Path zip, String name, List<EmoteFile> loaded) {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !relevant(entry.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try (InputStream in = archive.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(MAX_FILE + 1);
                    add(name + "/" + entry.getName(), bytes, loaded);
                }
            }
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Емоції: не вдалося відкрити " + zip, e);
        }
    }

    private void add(String path, byte[] bytes, List<EmoteFile> loaded) throws IOException {
        if (path.length() > MAX_PATH) {
            this.plugin.getLogger().warning("Емоції: шлях довший за " + MAX_PATH + " символів пропущено: " + path);
            return;
        }
        if (bytes.length > MAX_FILE) {
            this.plugin.getLogger().warning("Емоції: " + path + " файл емоції більший за ліміт");
            return;
        }
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) {
            gzip.write(bytes);
        }
        if (packed.size() > MAX_PACKED) {
            this.plugin.getLogger().warning("Емоції: " + path + " завеликий розмір пакету");
            return;
        }
        loaded.add(new EmoteFile(path, packed.toByteArray()));
    }

    private static boolean relevant(String lower) {
        return lower.endsWith(".json") || lower.endsWith(".png");
    }
}
