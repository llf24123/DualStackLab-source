package org.example.filemanager;

/** Compares digit runs without parsing them, including numbers larger than a long. */
final class NaturalOrder {
    static int compare(String a, String b) {
        int i = 0, j = 0, zeroTie = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (digit(x) && digit(y)) {
                int ae = i, be = j;
                while (ae < a.length() && digit(a.charAt(ae))) ae++;
                while (be < b.length() && digit(b.charAt(be))) be++;
                int az = i, bz = j;
                while (az < ae && a.charAt(az) == '0') az++;
                while (bz < be && b.charAt(bz) == '0') bz++;
                int length = Integer.compare(ae - az, be - bz);
                if (length != 0) return length;
                for (int p = 0; p < ae - az; p++) {
                    int value = Character.compare(a.charAt(az + p), b.charAt(bz + p));
                    if (value != 0) return value;
                }
                if (zeroTie == 0) zeroTie = Integer.compare(ae - i, be - j);
                i = ae; j = be;
            } else {
                int value = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (value != 0) return value;
                i++; j++;
            }
        }
        int length = Integer.compare(a.length() - i, b.length() - j);
        return length != 0 ? length : zeroTie != 0 ? zeroTie : a.compareTo(b);
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private NaturalOrder() { }
}
