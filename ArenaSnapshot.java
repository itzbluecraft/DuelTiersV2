package dev.tierduels;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A copy of every block in an arena region. After a duel the arena is compared with
 * this copy and every block that differs is put back.
 */
final class ArenaSnapshot {

    final String world;
    final int minX;
    final int minY;
    final int minZ;
    final int maxX;
    final int maxY;
    final int maxZ;
    private final int sizeX;
    private final int sizeZ;
    private final List<BlockData> palette;
    private final int[] indices;

    private ArenaSnapshot(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          List<BlockData> palette, int[] indices) {
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.sizeX = maxX - minX + 1;
        this.sizeZ = maxZ - minZ + 1;
        this.palette = palette;
        this.indices = indices;
    }

    /** Copies the region between two corners. Returns null if it has more than maxBlocks blocks. */
    static ArenaSnapshot capture(World w, Location a, Location b, long maxBlocks) {
        int minX = Math.min(a.getBlockX(), b.getBlockX());
        int maxX = Math.max(a.getBlockX(), b.getBlockX());
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        int minY = Math.max(w.getMinHeight(), Math.min(a.getBlockY(), b.getBlockY()));
        int maxY = Math.min(w.getMaxHeight() - 1, Math.max(a.getBlockY(), b.getBlockY()));

        long total = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (total > maxBlocks || total <= 0) return null;

        List<BlockData> palette = new ArrayList<>();
        Map<BlockData, Integer> lookup = new HashMap<>();
        int[] idx = new int[(int) total];

        int i = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockData data = w.getBlockAt(x, y, z).getBlockData();
                    Integer id = lookup.get(data);
                    if (id == null) {
                        id = palette.size();
                        palette.add(data);
                        lookup.put(data, id);
                    }
                    idx[i++] = id;
                }
            }
        }
        return new ArenaSnapshot(w.getName(), minX, minY, minZ, maxX, maxY, maxZ, palette, idx);
    }

    long blockCount() {
        return indices.length;
    }

    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    boolean inWorld(World w) {
        return w != null && w.getName().equals(world);
    }

    BoundingBox box() {
        return new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    /** Chunk coordinates {cx, cz} that the region touches. */
    List<int[]> chunks() {
        List<int[]> out = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                out.add(new int[]{cx, cz});
            }
        }
        return out;
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    /** Puts back every changed block of one chunk. Returns how many blocks were changed. */
    int restoreChunk(World w, int cx, int cz) {
        Chunk chunk = w.getChunkAt(cx, cz);
        int x0 = Math.max(minX, cx << 4);
        int x1 = Math.min(maxX, (cx << 4) + 15);
        int z0 = Math.max(minZ, cz << 4);
        int z1 = Math.min(maxZ, (cz << 4) + 15);

        int changed = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    Block block = chunk.getBlock(x & 15, y, z & 15);
                    BlockData want = palette.get(indices[index(x, y, z)]);
                    if (!block.getBlockData().equals(want)) {
                        block.setBlockData(want, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    int restoreAll(World w) {
        int changed = 0;
        for (int[] c : chunks()) changed += restoreChunk(w, c[0], c[1]);
        return changed;
    }

    // ------------------------------------------------------------ persistence

    void write(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeUTF(world);
            out.writeInt(minX);
            out.writeInt(minY);
            out.writeInt(minZ);
            out.writeInt(maxX);
            out.writeInt(maxY);
            out.writeInt(maxZ);
            out.writeInt(palette.size());
            for (BlockData d : palette) out.writeUTF(d.getAsString());
            out.writeInt(indices.length);
            for (int v : indices) out.writeInt(v);
        }
    }

    /** Returns null if the file is missing or cannot be read. */
    static ArenaSnapshot read(File file) {
        if (!file.exists()) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            String world = in.readUTF();
            int minX = in.readInt();
            int minY = in.readInt();
            int minZ = in.readInt();
            int maxX = in.readInt();
            int maxY = in.readInt();
            int maxZ = in.readInt();

            int paletteSize = in.readInt();
            List<BlockData> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) palette.add(Bukkit.createBlockData(in.readUTF()));

            int length = in.readInt();
            int[] idx = new int[length];
            for (int i = 0; i < length; i++) idx[i] = in.readInt();

            return new ArenaSnapshot(world, minX, minY, minZ, maxX, maxY, maxZ, palette, idx);
        } catch (IOException | IllegalArgumentException ex) {
            Bukkit.getLogger().warning("[TierDuels] Could not read arena snapshot " + file.getName()
                    + ": " + ex.getMessage());
            return null;
        }
    }
}
