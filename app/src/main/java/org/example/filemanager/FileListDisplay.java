package org.example.filemanager;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Settings shared by both panes; formatting is created on the listing worker. */
final class FileListDisplay {
    static int lines(Context c) { return Math.max(1, Math.min(8, FileManagerSettings.prefs(c).getInt("name-lines", 1))); }
    static String metadata(boolean showPermissions,FileStore.Node node,SimpleDateFormat formatter){
        boolean permissions=showPermissions&&node.unixMode>=0&&node.file!=null&&!StorageNavigation.appManagedStorage(node.file);
        String detail=permissions?permission(node.unixMode):formatter.format(new java.util.Date(node.modified));
        return detail+(node.directory?"":"  "+FileStore.size(node.size));
    }
    static String permission(int mode){StringBuilder result=new StringBuilder(9);for(int bit=8;bit>=0;bit--)result.append((mode&(1<<bit))!=0?"xwr".charAt(bit%3):'-');
        if((mode&04000)!=0)result.setCharAt(2,(mode&0100)!=0?'s':'S');if((mode&02000)!=0)result.setCharAt(5,(mode&0010)!=0?'s':'S');if((mode&01000)!=0)result.setCharAt(8,(mode&0001)!=0?'t':'T');return result.toString();}
    static SimpleDateFormat dates(Context c) {
        var p = FileManagerSettings.prefs(c);
        String pattern = p.getString("date-format", "yyyy-MM-dd HH:mm:ss");
        try { new SimpleDateFormat(pattern, Locale.CHINA); } catch (IllegalArgumentException e) { pattern = "yyyy-MM-dd HH:mm:ss"; }
        if (!p.getBoolean("seconds", false)) pattern = withoutSeconds(pattern);
        if (p.getBoolean("short-year", true)) pattern = shortYear(pattern);
        return new SimpleDateFormat(pattern, Locale.CHINA);
    }
    // Quoted literals must survive formatting changes, including escaped single quotes.
    private static String shortYear(String value) { return transform(value, true); }
    private static String withoutSeconds(String value) { return transform(value, false); }
    private static String transform(String value, boolean year) {
        StringBuilder out = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (c == '\'') { out.append(c); i++; if (i < value.length() && value.charAt(i) == '\'') { out.append('\''); i++; } else quoted = !quoted; continue; }
            if (!quoted && ((year && c == 'y') || (!year && (c == 's' || c == 'S')))) {
                int end = i + 1; while (end < value.length() && value.charAt(end) == c) end++;
                if (year) out.append(end - i >= 4 ? "yy" : value.substring(i, end));
                else if (out.length() > 0 && ":.,".indexOf(out.charAt(out.length() - 1)) >= 0) out.setLength(out.length() - 1);
                i = end; continue;
            }
            out.append(c); i++;
        }
        return out.toString().trim();
    }
    private FileListDisplay() { }
}
