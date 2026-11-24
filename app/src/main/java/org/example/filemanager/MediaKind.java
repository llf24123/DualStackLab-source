package org.example.filemanager;

import java.util.Locale;

/** Classifies a name without opening the file, including when binding recycled list rows. */
enum MediaKind {
    IMAGE("image", R.drawable.ic_image, 0xff526ba1),
    AUDIO("audio", R.drawable.ic_music, 0xff895582),
    VIDEO("video", R.drawable.ic_video, 0xff695c9a);

    final String preview;
    final int icon, tile;

    MediaKind(String preview, int icon, int tile) {
        this.preview = preview; this.icon = icon; this.tile = tile;
    }

    static MediaKind of(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif" -> IMAGE;
            case "mp3", "wav", "ogg", "m4a", "flac", "aac" -> AUDIO;
            case "mp4", "mkv", "webm", "3gp", "avi" -> VIDEO;
            default -> null;
        };
    }
}
