package dev.physicalcamera;

enum PhotoFormat {
    RAW("RAW",true,false), BOTH("RAW+PNG",true,true), PNG("PNG",false,true);

    final String label;
    final boolean raw,png;
    PhotoFormat(String label, boolean raw, boolean png) { this.label = label; this.raw = raw; this.png = png; }
}
