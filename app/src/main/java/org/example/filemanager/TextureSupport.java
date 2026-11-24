package org.example.filemanager;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.*;
import java.util.*;

/** Bundletool texture aliases backed by actual device capabilities. */
final class TextureSupport {
    private static volatile Set<Integer> cached;
    static Set<Integer> current(Context context) {
        Set<Integer> known = cached;
        if (known != null) return known;
        Set<Integer> result = new HashSet<>();
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000) result.add(10);
        Set<String> gl = extensions();
        if (gl.contains("GL_KHR_texture_compression_astc_ldr") || gl.contains("GL_KHR_texture_compression_astc_hdr")) result.add(9);
        if (gl.contains("GL_IMG_texture_compression_pvrtc")) result.add(8);
        if (gl.contains("GL_EXT_texture_compression_s3tc")) result.add(7);
        if (gl.contains("GL_EXT_texture_compression_dxt1") || result.contains(7)) result.add(6);
        if (gl.contains("GL_EXT_texture_compression_latc")) result.add(5);
        if (gl.contains("GL_AMD_compressed_ATC_texture") || gl.contains("GL_ATI_texture_compression_atitc")) result.add(4);
        if (gl.contains("GL_AMD_compressed_3DC_texture")) result.add(3);
        if (gl.contains("GL_OES_compressed_paletted_texture")) result.add(2);
        if (gl.contains("GL_OES_compressed_ETC1_RGB8_texture")) result.add(1);
        return cached = Set.copyOf(result);
    }
    private static Set<String> extensions() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, null, 0, null, 0)) return Set.of();
        EGLContext gl = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            int[] attributes = { EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE };
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return Set.of();
            gl = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (gl == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, surface, surface, gl)) return Set.of();
            String value = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            return value == null || value.isBlank() ? Set.of() : new HashSet<>(Arrays.asList(value.trim().split("\\s+")));
        } catch (RuntimeException unavailable) { return Set.of(); }
        finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (gl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, gl);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
    }

    private TextureSupport() { }
}
