package dev.physicalcamera;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Uncompressed TIFF/DNG with packed 10-bit or native-order 16-bit CFA samples. */
final class RawDng {
    static final int BITS = 10;
    static final int WHITE = (1<<BITS)-1;
    private static final int ROWS_PER_STRIP = 64;

    private record Tag(int id, int type, int count, byte[] data) {}

    private RawDng() {}

    static void write(Path path, int width, int height, short[] mosaic) throws IOException {
        write(path,width,height,mosaic,SensorFormat.Z8_FX);
    }

    static void write(Path path, int width, int height, short[] mosaic, SensorFormat sensor) throws IOException {
        if (width <= 0 || height <= 0 || (long)width*height != mosaic.length) {
            throw new IllegalArgumentException("RAW raster dimensions do not match the mosaic");
        }
        int bitsPerSample = sensor.rawBits();
        int white = (1<<bitsPerSample)-1;
        int rowBytes = (width*bitsPerSample+7)/8;
        int strips = (height+ROWS_PER_STRIP-1)/ROWS_PER_STRIP;
        int[] offsets = new int[strips], lengths = new int[strips];
        for (int strip = 0; strip < strips; strip++) {
            lengths[strip] = rowBytes*Math.min(ROWS_PER_STRIP,height-strip*ROWS_PER_STRIP);
        }
        List<Tag> tags = new ArrayList<>(List.of(
                longs(254,0), longs(256,width), longs(257,height), shorts(258,bitsPerSample),
                shorts(259,1), shorts(262,32803), shorts(266,1), shorts(274,1),
                ascii(271,"Dream Studio"), ascii(272,sensor.rawModel()),
                shorts(277,1), longs(278,ROWS_PER_STRIP), longs(279,lengths), shorts(284,1),
                shorts(339,1), shorts(33421,2,2), bytes(33422,0,1,1,2),
                bytes(50706,1,4,0,0), bytes(50707,1,1,0,0),
                ascii(50708,"Dream Studio "+sensor.rawModel()),
                bytes(50710,0,1,2), shorts(50711,1), shorts(50714,0), longs(50717,white),
                rationals(50721,true,324046,-153714,-49853,-96927,187601,4156,5564,-20403,105723),
                rationals(50728,false,100000,100000,100000), shorts(50778,21),
                longs(50829,0,0,height,width)));
        var offsetTag = longs(273,offsets);
        tags.add(offsetTag);
        tags.sort(Comparator.comparingInt(Tag::id));
        int directoryEnd = 8+2+12*tags.size()+4;
        int start = directoryEnd;
        for (Tag tag : tags) {
            if (tag.data.length > 4) {
                start += (tag.data.length+1)&~1;
            }
        }
        var stripTable = ByteBuffer.wrap(offsetTag.data).order(ByteOrder.LITTLE_ENDIAN);
        int cursor = start;
        for (int length : lengths) {
            stripTable.putInt(cursor);
            cursor = Math.addExact(cursor,length);
        }
        ByteBuffer header = ByteBuffer.allocate(start).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8).putShort((short)tags.size());
        int extra = directoryEnd;
        for (Tag tag : tags) {
            header.putShort((short)tag.id).putShort((short)tag.type).putInt(tag.count);
            if (tag.data.length <= 4) {
                header.put(tag.data).position(header.position()+4-tag.data.length);
            } else {
                header.putInt(extra);
                System.arraycopy(tag.data,0,header.array(),extra,tag.data.length);
                extra += (tag.data.length+1)&~1;
            }
        }
        header.putInt(0);
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(),"capture-",".tmp");
        try {
            try (var out = new BufferedOutputStream(Files.newOutputStream(temporary),256*1024)) {
                out.write(header.array());
                writeSamples(out,width,height,rowBytes,mosaic,bitsPerSample);
            }
            Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeSamples(OutputStream out, int width, int height, int rowBytes, short[] mosaic, int bitsPerSample)
            throws IOException {
        byte[] row = new byte[rowBytes];
        if (bitsPerSample == 16) {
            var samples = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            for (int y = 0; y < height; y++) {
                samples.clear().put(mosaic,y*width,width);
                out.write(row);
            }
            return;
        }
        for (int y = 0; y < height; y++) {
            int packed = 0, bits = 0, index = 0;
            for (int x = 0; x < width; x++) {
                packed = (packed<<BITS) | (mosaic[y*width+x]&WHITE);
                bits += BITS;
                while (bits >= 8) {
                    bits -= 8;
                    row[index++] = (byte)(packed>>>bits);
                }
            }
            if (bits > 0) {
                row[index] = (byte)(packed<<(8-bits));
            }
            out.write(row);
        }
    }

    private static Tag ascii(int id, String s) {
        byte[] data = (s+"\0").getBytes(StandardCharsets.US_ASCII);
        return new Tag(id,2,data.length,data);
    }

    private static Tag bytes(int id, int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            data[i] = (byte)values[i];
        }
        return new Tag(id,1,values.length,data);
    }

    private static Tag shorts(int id, int... values) {
        ByteBuffer data = buffer(2*values.length);
        for (int value : values) {
            data.putShort((short)value);
        }
        return new Tag(id,3,values.length,data.array());
    }

    private static Tag longs(int id, int... values) {
        ByteBuffer data = buffer(4*values.length);
        for (int value : values) {
            data.putInt(value);
        }
        return new Tag(id,4,values.length,data.array());
    }

    private static Tag rationals(int id, boolean signed, int... numerators) {
        ByteBuffer data = buffer(8*numerators.length);
        for (int value : numerators) {
            data.putInt(value).putInt(100000);
        }
        return new Tag(id,signed ? 10 : 5,numerators.length,data.array());
    }

    private static ByteBuffer buffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }
}
