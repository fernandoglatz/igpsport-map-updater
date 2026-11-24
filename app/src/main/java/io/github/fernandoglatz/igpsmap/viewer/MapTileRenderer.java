package io.github.fernandoglatz.igpsmap.viewer;

import io.github.fernandoglatz.igpsmap.domain.BoundingBox;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.core.model.Tile;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.renderer.DatabaseRenderer;
import org.mapsforge.map.layer.renderer.RendererJob;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.reader.header.MapFileException;
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes;
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture;

/**
 * Renders the tiles of one Mapsforge {@code .map} file with Mapsforge's default theme, the engine
 * Cruiser is built on. Uses AWT images only, so it needs no display. Not meant for concurrent use:
 * call it from one thread at a time (the methods are synchronized as a guard).
 */
public final class MapTileRenderer implements AutoCloseable {

    public static final int TILE_SIZE = 256;

    private final MapFile mapFile;
    private final DisplayModel displayModel = new DisplayModel();
    private final RenderThemeFuture theme;
    private final DatabaseRenderer renderer;
    private final BoundingBox boundingBox;
    private boolean closed;

    private MapTileRenderer(MapFile mapFile) {
        this.mapFile = mapFile;
        displayModel.setFixedTileSize(TILE_SIZE);
        theme = new RenderThemeFuture(AwtGraphicFactory.INSTANCE, MapsforgeThemes.DEFAULT, displayModel);
        theme.run(); // parses the theme now, so the first tile does not pay for it
        renderer = new DatabaseRenderer(mapFile, AwtGraphicFactory.INSTANCE, new InMemoryTileCache(16), null,
                true, true, null);
        org.mapsforge.core.model.BoundingBox box = mapFile.boundingBox();
        boundingBox = new BoundingBox(box.minLatitude, box.minLongitude, box.maxLatitude, box.maxLongitude);
    }

    /** Opens a map file; {@link IOException} when it is missing or not a valid Mapsforge map. */
    public static MapTileRenderer open(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }
        MapFile mapFile;
        try {
            mapFile = new MapFile(file.toFile());
        } catch (MapFileException e) {
            throw new IOException("Not a valid Mapsforge map: " + file.getFileName() + " (" + e.getMessage() + ")", e);
        }
        try {
            return new MapTileRenderer(mapFile);
        } catch (RuntimeException e) {
            mapFile.close();
            throw new IOException("Could not prepare " + file.getFileName() + " for rendering: " + e.getMessage(), e);
        }
    }

    public BoundingBox boundingBox() {
        return boundingBox;
    }

    /** Renders one tile as {@code TILE_SIZE * TILE_SIZE} opaque ARGB pixels, row by row. */
    public synchronized int[] render(TileKey key) throws IOException {
        if (closed) {
            throw new IllegalStateException("Renderer is closed");
        }
        try {
            theme.get(); // surfaces a theme error instead of an empty tile
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading the map theme", e);
        } catch (ExecutionException e) {
            throw new IOException("Map theme failed to load: " + e.getCause(), e.getCause());
        }
        Tile tile = new Tile(key.x(), key.y(), (byte) key.zoom(), TILE_SIZE);
        RendererJob job = new RendererJob(tile, mapFile, theme, displayModel, 1f, false, false);
        TileBitmap bitmap;
        try {
            bitmap = renderer.executeJob(job);
        } catch (RuntimeException e) {
            throw new IOException("Could not render tile " + key + ": " + e.getMessage(), e);
        }
        if (bitmap == null) {
            throw new IOException("Could not render tile " + key);
        }
        try {
            BufferedImage image = AwtGraphicFactory.getBitmap(bitmap);
            int[] pixels = new int[TILE_SIZE * TILE_SIZE];
            image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, pixels, 0, TILE_SIZE);
            return pixels;
        } finally {
            bitmap.decrementRefCount();
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            renderer.interruptAndDestroy();
            theme.decrementRefCount();
            mapFile.close();
        }
    }
}
