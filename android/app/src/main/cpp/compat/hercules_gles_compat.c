// Android GLES compatibility shims for desktop-GL entry points that
// gpu_gl_renderer.c calls directly but GLES does not provide.
//
// SDL's bundled SDL_opengl.h declares the desktop GL prototypes, so the
// renderer compiles on Android; GLES only implements the GLES subset, which
// leaves these symbols undefined at link time:
//   - glClearDepth  : GLES has glClearDepthf (GLfloat) instead
//   - glGetTexImage : GLES has no direct equivalent (there is glReadPixels)
//
// glReadBuffer IS part of GLES 3.0 and is provided by libGLESv3.so, so it only
// needs linking (see android/app/src/main/cpp/CMakeLists.txt).
//
// glGetTexImage is only used by the renderer's debug dumps (stereo/pass dumps),
// but the implementation below is real: it attaches the bound texture to a
// scratch FBO and reads it back through glReadPixels, converting RGBA -> RGB
// when the caller asked for GL_RGB.

#include <GLES3/gl3.h>
#include <stddef.h>
#include <stdlib.h>

// glGetTexLevelParameteriv and GL_TEXTURE_WIDTH/HEIGHT are OpenGL ES 3.1
// additions (gl31.h); this shim builds against the ES 3.0 header set. The
// symbol IS exported by libGLESv3.so, and the only caller is the renderer's
// debug dump path, so a direct declaration is enough.
#ifndef GL_TEXTURE_WIDTH
#define GL_TEXTURE_WIDTH 0x1000
#endif
#ifndef GL_TEXTURE_HEIGHT
#define GL_TEXTURE_HEIGHT 0x1001
#endif
extern void glGetTexLevelParameteriv(GLenum target, GLint level, GLenum pname, GLint *params);

// SDL's SDL_opengl.h declares: void glClearDepth(GLclampd depth);
// GLclampd is a typedef for double, and GLES has no GLclampd, so spell it out.
void glClearDepth(double depth) { glClearDepthf((GLfloat)depth); }

void glGetTexImage(GLenum target, GLint level, GLenum format, GLenum type, void *pixels)
{
    if (target != GL_TEXTURE_2D) return;
    if (type != GL_UNSIGNED_BYTE) return;
    if (format != GL_RGB && format != GL_RGBA) return;

    GLint tex = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex);
    if (tex == 0) return;

    GLint w = 0;
    GLint h = 0;
    glGetTexLevelParameteriv(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH, &w);
    glGetTexLevelParameteriv(GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT, &h);
    if (w <= 0 || h <= 0) return;

    const size_t count = (size_t)w * (size_t)h;
    unsigned char *rgba = (unsigned char *)malloc(count * 4u);
    if (!rgba) return;

    GLint prev_read = 0;
    GLint prev_draw = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &prev_read);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &prev_draw);

    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           (GLuint)tex, level);

    if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    } else {
        // Not color-renderable (e.g. a compressed format): leave no garbage.
        for (size_t i = 0; i < count * 4u; ++i) rgba[i] = 0;
    }

    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)prev_read);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)prev_draw);
    glDeleteFramebuffers(1, &fbo);

    unsigned char *dst = (unsigned char *)pixels;
    if (format == GL_RGB) {
        for (size_t i = 0; i < count; ++i) {
            dst[i * 3 + 0] = rgba[i * 4 + 0];
            dst[i * 3 + 1] = rgba[i * 4 + 1];
            dst[i * 3 + 2] = rgba[i * 4 + 2];
        }
    } else {
        for (size_t i = 0; i < count * 4u; ++i) dst[i] = rgba[i];
    }

    free(rgba);
}