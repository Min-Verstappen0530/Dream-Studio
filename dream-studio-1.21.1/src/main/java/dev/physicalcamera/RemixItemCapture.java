package dev.physicalcamera;

public final class RemixItemCapture {
    private static final ThreadLocal<Boolean> EQUIPMENT = new ThreadLocal<>();

    private RemixItemCapture() {}

    public static boolean active() { return Boolean.TRUE.equals(EQUIPMENT.get()); }

    public static boolean begin(boolean equipment) {
        boolean previous = active();
        if (equipment != previous) set(equipment);
        return previous;
    }

    public static void end(boolean previous) {
        if (previous != active()) set(previous);
    }

    private static void set(boolean equipment) {
        if (equipment) EQUIPMENT.set(Boolean.TRUE);
        else EQUIPMENT.remove();
    }
}
