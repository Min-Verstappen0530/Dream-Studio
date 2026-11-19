package dev.physicalcamera;

public final class Z8Presentation {
    public static final float SCALE = .74f;
    public static final float DEPTH = -.63f;
    // Rear glass bounds measured in the exported 16-unit item coordinates.
    private static final Panel Z8 = new Panel(2.04,10.03,8.35,3.01,10.965);
    private static final Panel X2D_II = new Panel(1.59,9.74,9.56,3.44,13.028);

    private Z8Presentation() {}

    public static float x(float lift) { return .10f*(1-lift); }
    public static float y(float lift) { return -.035f-.46f*(1-lift); }

    static Glass project(int width, int height, float fov) {
        return project(width,height,fov,SensorFormat.Z8_FX);
    }

    static Glass project(int width, int height, float fov, SensorFormat sensor) {
        Panel panel = sensor == SensorFormat.X2D_II ? X2D_II : Z8;
        double pixelsPerUnit = height/(-2*(DEPTH+itemOffset(panel.depth))*Math.tan(Math.toRadians(fov/2)));
        double left = width*.5+(x(1)+itemOffset(panel.left))*pixelsPerUnit;
        double right = width*.5+(x(1)+itemOffset(panel.right))*pixelsPerUnit;
        double top = height*.5-(y(1)+itemOffset(panel.top))*pixelsPerUnit;
        double bottom = height*.5-(y(1)+itemOffset(panel.bottom))*pixelsPerUnit;
        return new Glass(left,top,right-left,bottom-top);
    }

    private static double itemOffset(double coordinate) { return (coordinate/16-.5)*SCALE; }
    private record Panel(double left, double right, double top, double bottom, double depth) {}
    record Glass(double x, double y, double width, double height) {}
}
