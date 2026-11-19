package dev.physicalcamera;

public enum Z8PhotoSize {
    HD(1920,1280,"1920×1280"),
    UHD(3840,2560,"3840×2560"),
    FULL(SensorFormat.FULL_FRAME_45MP.columns(),SensorFormat.FULL_FRAME_45MP.rows(),"8256×5504"),
    MF_HD(1920,1440,"1920×1440"),
    MF_UHD(3840,2880,"3840×2880"),
    MF_FULL(SensorFormat.X2D_II.columns(),SensorFormat.X2D_II.rows(),"11656×8742");

    private static final Z8PhotoSize[] FULL_FRAME = {HD,UHD,FULL};
    private static final Z8PhotoSize[] MEDIUM_FORMAT = {MF_HD,MF_UHD,MF_FULL};

    public final int width, height;
    final String label;

    Z8PhotoSize(int width, int height, String label) {
        this.width = width;
        this.height = height;
        this.label = label;
    }

    double aspect() { return width/(double)height; }

    SensorFormat sensor() { return ordinal() < FULL_FRAME.length ? SensorFormat.Z8_FX : SensorFormat.X2D_II; }
    static Z8PhotoSize[] forSensor(SensorFormat sensor) { return sensor == SensorFormat.X2D_II ? MEDIUM_FORMAT : FULL_FRAME; }
    int sizeIndex() { return ordinal()%FULL_FRAME.length; }
    Z8PhotoSize renderSize(boolean iris) {
        if (this == MF_FULL || iris && this == FULL) return forSensor(sensor())[1];
        return this;
    }

    public double halfTan(double focal) { return sensor().gateWidth(aspect())/(2*aspect()*focal); }

    double viewportHalfTan(double focal, double viewportAspect) {
        return halfTan(focal)*Math.max(1,aspect()/viewportAspect);
    }
}
