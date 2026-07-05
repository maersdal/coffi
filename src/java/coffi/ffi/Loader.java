package coffi.ffi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loading libraries with the {@link System#load} and {@link System#loadLibrary}
 * relies on the classloader, which Clojure messes with, and pins the library
 * for the lifetime of the JVM. This class exists to have a consistent way to
 * load libraries and symbols from them, with library lifetimes tied to an
 * {@link Arena} rather than a classloader so they can be unloaded and reloaded
 * without restarting the JVM.
 */
public class Loader {

    static final SymbolLookup systemLookup =
        Linker.nativeLinker().defaultLookup().or(SymbolLookup.loaderLookup());

    record Library(Arena arena, SymbolLookup lookup, byte[] contentHash) {}

    static final Map<String, Library> libraries = new LinkedHashMap<>();

    /**
     * Cache of resolved symbol addresses, so that re-resolving a symbol on
     * every call is just a map lookup. Invalidated whenever a library is
     * loaded or unloaded.
     */
    static final ConcurrentHashMap<String, MemorySegment> symbolCache = new ConcurrentHashMap<>();

    /**
     * Loads a library from a given absolute file path.
     *
     * If a library was already loaded from this path and the file's contents
     * are unchanged, this is a no-op. If the contents have changed (e.g. it
     * was recompiled), the old library is unloaded and the new one loaded in
     * its place; symbols already resolved from the old copy become invalid.
     *
     * @param filepath The absolute file path of the library to load
     */
    public static synchronized void loadLibrary(String filepath) {
        byte[] contentHash = hashFile(filepath);
        Library existing = libraries.get(filepath);
        if (existing != null
            && MessageDigest.isEqual(existing.contentHash(), contentHash)) {
            return;
        }
        unloadLibrary(filepath);
        Arena arena = Arena.ofShared();
        try {
            libraries.put(filepath, new Library(arena, SymbolLookup.libraryLookup(filepath, arena), contentHash));
            symbolCache.clear();
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    static byte[] hashFile(String filepath) {
        try (InputStream in = Files.newInputStream(Path.of(filepath))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read library file: " + filepath, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Unloads the library previously loaded from the given file path.
     *
     * Any {@link MemorySegment}s and method handles derived from its symbols
     * become invalid; using them afterwards may crash the JVM.
     *
     * @param filepath The absolute file path the library was loaded from
     */
    public static synchronized void unloadLibrary(String filepath) {
        Library library = libraries.remove(filepath);
        if (library != null) {
            library.arena().close();
            symbolCache.clear();
        }
    }

    /**
     * Loads a library on the system loadpath with the given name.
     *
     * Libraries loaded this way are pinned until the JVM exits.
     *
     * @param libname The library name, stripped of platform-specific prefixes and suffixes.
     */
    public static void loadSystemLibrary(String libname) {
        System.loadLibrary(libname);
    }

    /**
     * Load the memory address of a symbol.
     *
     * First attempts to load the symbol from libraries loaded with
     * {@link #loadLibrary}, and afterwards from system libraries, like libc.
     *
     * Resolved addresses are cached until the next library load or unload,
     * so calling this is cheap enough to do on every native call.
     *
     * @param symbol The name of the symbol to load from a library.
     */
    public static MemorySegment findSymbol(String symbol) {
        MemorySegment cached = symbolCache.get(symbol);
        if (cached != null) {
            return cached;
        }
        return resolveSymbol(symbol);
    }

    static synchronized MemorySegment resolveSymbol(String symbol) {
        Optional<MemorySegment> address = Optional.empty();
        for (Library library : libraries.values()) {
            address = library.lookup().find(symbol);
            if (address.isPresent()) {
                break;
            }
        }
        if (address.isEmpty()) {
            address = systemLookup.find(symbol);
        }
        if (address.isPresent()) {
            symbolCache.put(symbol, address.get());
            return address.get();
        }
        return null;
    }

    /**
     * Like {@link #findSymbol}, but throws if the symbol cannot be resolved.
     *
     * @param symbol The name of the symbol to load from a library.
     */
    public static MemorySegment requireSymbol(String symbol) {
        MemorySegment address = findSymbol(symbol);
        if (address == null) {
            throw new UnsatisfiedLinkError("Could not resolve native symbol: " + symbol);
        }
        return address;
    }
}
