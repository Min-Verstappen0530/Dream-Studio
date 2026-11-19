package dev.physicalcamera;

record Z8ReadRegion(int x, int y, int width, int height) {
    static Z8ReadRegion centered(int width, int height, Z8Frame.Crop crop, Z8PhotoSize size) {
        boolean medium = size.sensor() == SensorFormat.X2D_II;
        int columns = medium ? 4 : 3, rows = medium ? 3 : 2;
        int units = (int)Math.floor(Math.min(width*2*crop.halfWidth()/columns,
                height*2*crop.halfHeight()/rows)+1e-6);
        int w = units > 0 ? units*columns : width;
        int h = units > 0 ? units*rows : height;
        return new Z8ReadRegion((width-w)/2,(height-h)/2,w,h);
    }

    int bottom(int frameHeight) { return frameHeight-y-height; }
}
