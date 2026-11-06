import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDL_Event;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLKeycode.*;
import static org.lwjgl.sdl.SDLMouse.SDL_SetWindowRelativeMouseMode;
import static org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty;
import static org.lwjgl.sdl.SDLVideo.*;

/**
 * Stand-in for the Minecraft 26.x client: LWJGL 3.4.3 + SDL3 window + OpenGL, a render loop and an event pump.
 *
 * stdin commands (sent by the host):
 *   stall <ms>  block the main thread without pumping window messages (simulates a GC pause / world load)
 *   quit
 * keys: S = stall 3 s, G = grab mouse (relative mode, like in-game), ESC = release
 */
public class SdlTarget {
    static volatile long stallRequestMs;
    static volatile boolean quit;

    public static void main(String[] args) throws Exception {
        Thread.ofPlatform().daemon().name("stdin").start(() -> {
            try (var r = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = r.readLine()) != null) {
                    var p = line.trim().split("\\s+");
                    switch (p[0]) {
                        case "stall" -> stallRequestMs = Long.parseLong(p[1]);
                        case "quit" -> quit = true;
                        default -> out("unknown command " + line);
                    }
                }
            } catch (Exception ignored) {
            }
            quit = true;   // host went away
        });

        if (!SDL_Init(SDL_INIT_VIDEO)) throw new IllegalStateException("SDL_Init: " + SDL_GetError());
        long win = SDL_CreateWindow("SF-Target (SDL3 + OpenGL)", 854, 480, SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE);
        if (win == 0) throw new IllegalStateException("SDL_CreateWindow: " + SDL_GetError());
        long ctx = SDL_GL_CreateContext(win);
        SDL_GL_MakeCurrent(win, ctx);
        GL.createCapabilities();
        SDL_GL_SetSwapInterval(1);
        long hwnd = SDL_GetPointerProperty(SDL_GetWindowProperties(win), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        // off by default, like vanilla Minecraft; the host handles click-to-focus (SF_CLICK_TO_FOCUS=1 to compare)
        boolean clickToFocus = "1".equals(System.getenv("SF_CLICK_TO_FOCUS"));
        out("READY pid="+ ProcessHandle.current().pid() + " gl=" + glGetString(GL_VERSION) + " renderer=" + glGetString(GL_RENDERER));

        int w = 854, h = 480;
        long frames = 0, fpsT0 = System.nanoTime();
        try (var ev = SDL_Event.calloc()) {
            while (!quit) {
                long stall = stallRequestMs;
                if (stall > 0) {
                    stallRequestMs = 0;
                    out("STALL begin " + stall + "ms");
                    Thread.sleep(stall);            // no event pumping => window is "not responding"
                    out("STALL end");
                }
                while (SDL_PollEvent(ev)) {
                    int t = ev.type();
                    if (t == SDL_EVENT_QUIT) quit = true;
                    else if (t == SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED) {
                        w = ev.window().data1(); h = ev.window().data2();
                        out("EVT pixelsize " + w + "x" + h);
                    } else if (t == SDL_EVENT_WINDOW_FOCUS_GAINED) out("EVT focus gained");
                    else if (t == SDL_EVENT_WINDOW_FOCUS_LOST) out("EVT focus lost");
                    else if (t == SDL_EVENT_MOUSE_BUTTON_DOWN) {
                        out("EVT mouse down");
                        // A WS_CHILD window does not take keyboard focus on click by itself. This is the one-line
                        // hook the real mod needs: when embedded, grab focus on click (works because SetParent
                        // attached our input queue to the host's).
                        if (clickToFocus && (Win32.getLongPtr(hwnd, Win32.GWL_STYLE) & Win32.WS_CHILD) != 0
                                && Win32.getFocus() != hwnd) {
                            Win32.setFocus(hwnd);
                        }
                    }
                    else if (t == SDL_EVENT_KEY_DOWN) {
                        int k = ev.key().key();
                        out("EVT key " + k);
                        if (k == SDLK_S) stallRequestMs = 3000;
                        else if (k == SDLK_G) out("GRAB " + SDL_SetWindowRelativeMouseMode(win, true));
                        else if (k == SDLK_ESCAPE) out("UNGRAB " + SDL_SetWindowRelativeMouseMode(win, false));
                    }
                }

                // draw something that visibly moves, using only clear + scissor (works on any GL profile)
                double s = (System.nanoTime() / 1e9);
                glViewport(0, 0, w, h);
                glDisable(GL_SCISSOR_TEST);
                glClearColor((float) (0.15 + 0.1 * Math.sin(s)), 0.18f, (float) (0.25 + 0.1 * Math.cos(s)), 1f);
                glClear(GL_COLOR_BUFFER_BIT);
                glEnable(GL_SCISSOR_TEST);
                int bw = Math.max(20, w / 8), bh = Math.max(20, h / 8);
                int bx = (int) ((w - bw) * (0.5 + 0.5 * Math.sin(s * 1.3)));
                int by = (int) ((h - bh) * (0.5 + 0.5 * Math.cos(s * 0.9)));
                glScissor(bx, by, bw, bh);
                glClearColor(0.95f, 0.8f, 0.2f, 1f);   // sulfur yellow
                glClear(GL_COLOR_BUFFER_BIT);
                SDL_GL_SwapWindow(win);

                frames++;
                if (System.nanoTime() - fpsT0 > 1_000_000_000L) {
                    SDL_SetWindowTitle(win, "SF-Target " + frames + " fps " + w + "x" + h);
                    frames = 0; fpsT0 = System.nanoTime();
                }
            }
        }
        SDL_GL_DestroyContext(ctx);
        SDL_DestroyWindow(win);
        SDL_Quit();
        out("BYE");
    }

    static void out(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
