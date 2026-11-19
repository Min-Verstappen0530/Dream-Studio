package dev.physicalcamera;

/** Active imaging dimensions in mm and native photosite counts. */
public record SensorFormat(double widthMm, double heightMm, int columns, int rows) {
    static final SensorFormat FULL_FRAME_45MP = new SensorFormat(35.9,23.9,8256,5504);
    public static final SensorFormat Z8_FX = FULL_FRAME_45MP;
    public static final SensorFormat X2D_II = new SensorFormat(43.8,32.9,11656,8742);

    double pixelArea() { return widthMm*heightMm/((double)columns*rows); }
    double gateWidth(double aspect) { return Math.min(widthMm,heightMm*aspect); }
    double gateArea(double aspect) { return Math.pow(gateWidth(aspect),2)/aspect; }
    int rawBits() { return this == X2D_II ? 16 : RawDng.BITS; }
    String rawModel() { return this == X2D_II ? "Simulated X2D II 100C Sensor" : "Simulated RGB Sensor"; }

    RecordingMedia.SensorResponse response(Z8PhotoSize size) {
        // Reduced resolutions pool native photosites in the active sensor crop.
        double area = columns == size.width && rows == size.height ? pixelArea()
                : gateArea(size.aspect())/((double)size.width*size.height);
        return new RecordingMedia.SensorResponse(area,area/pixelArea(),rawBits());
    }
}
