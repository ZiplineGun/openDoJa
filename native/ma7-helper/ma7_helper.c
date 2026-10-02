/*
 * ma7_helper.c - 32-bit out-of-process host for the Yamaha MA-7 emulator DLLs.
 *
 * The MA-7 DLLs (M7_EmuSmw7.dll / SMAFMMS7EMU.dll / M7_EmuHw.dll) are x86-only,
 * so a 64-bit JVM cannot load them. This helper is loaded as a 32-bit child
 * process by openDoJa and talks to it over stdin/stdout with a small framed
 * binary protocol. The DLL call sequence is a MA7-only port of the one used
 * by foo_mmf (src/yamaha.cpp), which is known to work with these libraries.
 *
 * Portions of this file are derived from foo_mmf (src/yamaha.cpp), which is
 * distributed under the following license:
 *
 * MIT License
 *
 * Copyright (c) 2026 foo_mmf contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * Protocol (all integers little-endian):
 *   Header  : u32 magic 'MA7H', u32 version, u32 command, u32 payloadBytes
 *   OPEN  1 : u32 fileBytes, u32 folderChars, u32 rate, i32 volume, u32 loopCount,
 *             UTF-16 folder[folderChars], file[fileBytes]
 *             -> u32 lengthMs, u32 rate, u32 channels
 *   READ  2 : i32 tempo, i32 transpose, i32 volume, u32 blocks
 *             -> u32 eof, float32 interleaved stereo PCM (blocks * rate/100 frames)
 *   SEEK  3 : u64 milliseconds -> (empty)
 *   QUIT  4 : (no reply)
 *   error   : command 0xFFFF, payload = ASCII message; helper exits afterwards.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>

#define MAGIC 0x48374d41u
#define VERSION 1u
#define MAX_PAYLOAD (65u * 1024u * 1024u)
#define MAX_FILE (64u * 1024u * 1024u)
#define MAX_BLOCK_FRAMES 480
#define MAX_BLOCKS 32

enum { CMD_OPEN = 1, CMD_READ = 2, CMD_SEEK = 3, CMD_QUIT = 4, CMD_ERROR = 0xffff };

typedef struct { uint32_t magic, version, command, bytes; } Header;

static HANDLE g_in, g_out;
static jmp_buf g_fail;
static char g_msg[256];

static void fail(const char *fmt, ...) __attribute__((noreturn));
#include <stdarg.h>
static void fail(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_msg, sizeof g_msg, fmt, ap);
    va_end(ap);
    longjmp(g_fail, 1);
}

/* ---------- pipe I/O ---------- */
static int io_all(HANDLE h, void *data, size_t size, int writing)
{
    uint8_t *p = (uint8_t *)data;
    while (size) {
        DWORD done = 0, chunk = size > 65536 ? 65536 : (DWORD)size;
        BOOL ok = writing ? WriteFile(h, p, chunk, &done, NULL) : ReadFile(h, p, chunk, &done, NULL);
        if (!ok || !done) return 0;
        p += done;
        size -= done;
    }
    return 1;
}

static void send_msg(uint32_t command, const void *data, uint32_t bytes)
{
    Header h = { MAGIC, VERSION, command, bytes };
    if (!io_all(g_out, &h, sizeof h, 1)) ExitProcess(3);
    if (bytes && !io_all(g_out, (void *)data, bytes, 1)) ExitProcess(3);
}

/* ---------- Yamaha engine (MA7 only) ---------- */
typedef int (__cdecl *fnCall)(void);
typedef int (__cdecl *fnOne)(int);
typedef int (__cdecl *fnControl)(int, unsigned, void *);
typedef int (__cdecl *fnGenerate)(void *, unsigned);

static HMODULE g_smw, g_mms, g_hw;
static DLL_DIRECTORY_COOKIE g_cookie;
static wchar_t g_folder[32768];
static int g_haveEngine, g_emulator, g_initialized, g_opened, g_started;
static int g_handle;
static unsigned g_rate, g_frames, g_length;
static int g_tempo = 100, g_transpose, g_volume = 64;
static int g_ending, g_finished;
static unsigned g_tailFrames, g_quietFrames;
static uint64_t g_renderedTenthsMs;
static int g_lastPosition = -1;
static unsigned g_loopCount = 1;
static uint8_t *g_data;
static fnControl g_control;
static fnGenerate g_generate;

static FARPROC api(const char *name)
{
    FARPROC p = GetProcAddress(g_smw, name);
    if (!p) fail("Missing Yamaha export: %s", name);
    return p;
}
static int checked(const char *name, int value)
{
    if (value < 0) fail("Yamaha %s failed (%d)", name, value);
    return value;
}
#define MELODY(name) ((void *)api("Mapi_Melody_" name))

static void reset_device(void)
{
    typedef int (__cdecl *DevEx)(unsigned, unsigned, void *);
    DevEx dev = (DevEx)api("Mapi_DeviceControlEx");
    uint32_t gain[3] = { 0, 2, 0 };
    unsigned outs[3] = { 0, 2, 1 }, route = 2, analog = 0, i;
    /* Without this hardware reset the digital master gain stays at zero. */
    checked("ResetDevice", dev(0x10000, 0, NULL));
    for (i = 0; i < 3; ++i) checked("OutputGain", dev(0x10010, outs[i], gain));
    for (i = 0; i < 2; ++i) {
        checked("OutputRoute", dev(0x10018, i, &route));
        checked("AnalogGain", dev(0x1001a, i, &analog));
    }
}

static void initialize(void)
{
    typedef int (__cdecl *Init)(int, void *, void *, void *, void *, void *);
    static const uint16_t dims[16] = { 120,120,120,120,480,640,480,640,12,12,12,12,16,16,24,24 };
    uint32_t config[18];
    memset(config, 0, sizeof config);
    config[0] = 2; config[1] = g_rate; config[4] = 15; config[5] = config[6] = 1;
    memcpy(&config[7], dims, sizeof dims);
    config[16] = 0xddd; config[17] = 1;
    checked("EmuInitialize", ((Init)api("Mapi_EmuInitialize"))(0, config, NULL, NULL, NULL, NULL));
    g_emulator = 1;
    checked("Initialize", ((fnCall)api("Mapi_Initialize"))());
    g_initialized = 1;
    reset_device();
    checked("SetMode", ((fnOne)api("Mapi_SetMode"))(1));
    g_control = (fnControl)api("Mapi_Melody_Control");
    g_generate = (fnGenerate)api("Mapi_EmuGenerate");
}

static void terminate(void)
{
    if (g_initialized) checked("Terminate", ((fnCall)api("Mapi_Terminate"))());
    if (g_emulator) checked("EmuTerminate", ((fnCall)api("Mapi_EmuTerminate"))());
    g_initialized = g_emulator = 0;
}

static void load_libraries(const wchar_t *dir, unsigned rate)
{
    wchar_t path[MAX_PATH * 2];
    DWORD flags = LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS;
    SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32 | LOAD_LIBRARY_SEARCH_USER_DIRS);
    g_cookie = AddDllDirectory(dir);
    if (!g_cookie) fail("Cannot use the configured MA-7 DLL folder");
    g_rate = rate;
    g_frames = rate / 100;
    _snwprintf(path, sizeof path / sizeof *path, L"%ls\\M7_EmuSmw7.dll", dir);
    g_smw = LoadLibraryExW(path, NULL, flags);
    if (!g_smw) fail("Cannot load M7_EmuSmw7.dll or its dependencies (Windows error %lu)", GetLastError());
    /* SMAFMMS7EMU.dll implicitly links against EMOJIMGR.dll (unrelated to
       audio: an emoji-image manager from the same phone SDK), so that file
       must sit alongside it for this call to succeed. */
    _snwprintf(path, sizeof path / sizeof *path, L"%ls\\SMAFMMS7EMU.dll", dir);
    g_mms = LoadLibraryExW(path, NULL, flags);
    _snwprintf(path, sizeof path / sizeof *path, L"%ls\\M7_EmuHw.dll", dir);
    g_hw = LoadLibraryExW(path, NULL, flags);
    if (!g_mms || !g_hw) fail("Cannot load SMAFMMS7EMU.dll / M7_EmuHw.dll");
    g_haveEngine = 1;
    initialize();
}

static void unload_libraries(void)
{
    if (g_haveEngine) {
        /* Best effort at shutdown: never longjmp out of here. */
        if (g_initialized) ((fnCall)GetProcAddress(g_smw, "Mapi_Terminate"))();
        if (g_emulator) ((fnCall)GetProcAddress(g_smw, "Mapi_EmuTerminate"))();
        g_initialized = g_emulator = 0;
    }
    if (g_hw) FreeLibrary(g_hw);
    if (g_mms) FreeLibrary(g_mms);
    if (g_smw) FreeLibrary(g_smw);
    if (g_cookie) RemoveDllDirectory(g_cookie);
    g_hw = g_mms = g_smw = NULL; g_cookie = NULL; g_haveEngine = 0;
}

static int song_control(unsigned cmd, void *value) { return g_control(g_handle, cmd, value); }

static void controls(int tempo, int transpose, int volume)
{
    if (tempo < 70 || tempo > 130 || transpose < -12 || transpose > 12) fail("Tempo or transposition out of range");
    if (volume < 0 || volume > 127) fail("Yamaha volume out of range");
    checked("Tempo", song_control(0x11110029, &tempo));
    checked("Transpose", song_control(0x11110030, &transpose));
    checked("Volume", song_control(0x1111002c, &volume));
    g_tempo = tempo; g_transpose = transpose; g_volume = volume;
}

static void close_song(void)
{
    int r;
    if (g_started) {
        r = ((fnOne)MELODY("Stop"))(g_handle);
        if (r != -8) checked("Stop", r);
        g_started = 0;
    }
    if (g_opened) { checked("Close", ((fnOne)MELODY("Close"))(g_handle)); g_opened = 0; }
    if (g_handle) { checked("Unload", ((fnOne)MELODY("Unload"))(g_handle)); g_handle = 0; }
    free(g_data); g_data = NULL;
}

static void open_song(uint8_t *data, unsigned size, int volume, unsigned loops)
{
    uint32_t args[6] = { 0 };
    int loopCount;
    int reset = g_handle != 0;
    close_song();
    if (reset) { terminate(); initialize(); } /* clear all chip/effect state */
    g_data = data;
    g_loopCount = loops ? loops : 1;
    g_tailFrames = g_quietFrames = 0;
    g_renderedTenthsMs = 0; g_lastPosition = -1; g_ending = g_finished = 0;
    typedef int (__cdecl *Load)(const void *, unsigned, unsigned, unsigned);
    g_handle = checked("Load", ((Load)MELODY("Load"))(g_data, size, 0, 1));
    if (!g_handle) fail("Yamaha returned an invalid song handle");
    ((void (__cdecl *)(void *))(void *)MELODY("OpenArgInit"))(args);
    typedef int (__cdecl *Open)(int, void *);
    checked("Open", ((Open)MELODY("Open"))(g_handle, args));
    g_opened = 1;
    loopCount = (int)g_loopCount;
    checked("RepeatCount", song_control(0x11110034, &loopCount));
    typedef int (__cdecl *Standby)(int, unsigned, int);
    checked("Standby", ((Standby)MELODY("Standby"))(g_handle, 0, 0));
    checked("WaitReady", ((int (__cdecl *)(int, int))MELODY("WaitReady"))(g_handle, -1));
    g_length = (unsigned)checked("Length", song_control(0x10100005, NULL));
    if (!g_length || g_length > 24u * 60u * 60u * 1000u) fail("Invalid Yamaha song length");
    controls(100, 0, volume);
    checked("Start", ((fnOne)MELODY("Start"))(g_handle));
    g_started = 1;
}

static void seek_song(uint64_t ms)
{
    int r;
    unsigned position;
    if (!g_handle) fail("No song is open");
    g_finished = 0;
    if (ms >= g_length) { g_finished = 1; return; }
    r = ((fnOne)MELODY("Stop"))(g_handle);
    if (r != -8) checked("SeekStop", r);
    g_started = 0;
    position = (unsigned)ms;
    checked("Seek", ((int (__cdecl *)(int, unsigned))MELODY("Seek"))(g_handle, position));
    checked("SeekReady", ((int (__cdecl *)(int, int))MELODY("WaitReady"))(g_handle, -1));
    controls(g_tempo, g_transpose, g_volume);
    checked("SeekStart", ((fnOne)MELODY("Start"))(g_handle));
    g_started = 1;
    g_renderedTenthsMs = (uint64_t)position * 10;
    g_lastPosition = -1; g_ending = 0; g_tailFrames = g_quietFrames = 0;
}

/* Renders one 10 ms block. Returns frame count, or 0 once the song and its tail are done. */
static unsigned render_block(float *out)
{
    int32_t left[MAX_BLOCK_FRAMES], right[MAX_BLOCK_FRAMES];
    int32_t *planes[8] = { left, right };
    int position, peak = 0, stoppedNearEnd;
    unsigned i;
    if (g_finished) return 0;
    memset(left, 0, sizeof left); memset(right, 0, sizeof right);
    checked("Generate", g_generate(planes, g_frames));
    if (!g_ending) g_renderedTenthsMs += (uint64_t)g_tempo;
    position = checked("Position", song_control(0x1111002b, NULL));
    /* MA7 can stop up to one block short on MLD files while Generate keeps returning PCM. */
    stoppedNearEnd = position == g_lastPosition && position >= (int)g_length - 10;
    g_lastPosition = position;
    if (position >= (int)g_length || stoppedNearEnd) g_ending = 1;
    if (g_renderedTenthsMs >= (uint64_t)g_length * 10) g_ending = 1;
    for (i = 0; i < g_frames; ++i) {
        int l = left[i], r = right[i];
        l = l < -32768 ? -32768 : l > 32767 ? 32767 : l;
        r = r < -32768 ? -32768 : r > 32767 ? 32767 : r;
        if (abs(l) > peak) peak = abs(l);
        if (abs(r) > peak) peak = abs(r);
        left[i] = l; right[i] = r;
        out[i * 2] = l / 32768.0f;
        out[i * 2 + 1] = r / 32768.0f;
    }
    if (g_ending) {
        int32_t lmin = left[0], lmax = left[0], rmin = right[0], rmax = right[0];
        int quiet;
        for (i = 1; i < g_frames; ++i) {
            if (left[i] < lmin) lmin = left[i];
            if (left[i] > lmax) lmax = left[i];
            if (right[i] < rmin) rmin = right[i];
            if (right[i] > rmax) rmax = right[i];
        }
        g_tailFrames += g_frames;
        /* The emulator can retain a small DC offset after its reverb ends. */
        quiet = peak <= 64 && lmax - lmin <= 2 && rmax - rmin <= 2;
        g_quietFrames = quiet ? g_quietFrames + g_frames : 0;
        if (g_quietFrames >= g_rate / 4 || g_tailFrames >= g_rate * 10) g_finished = 1;
    }
    return g_frames;
}

/* ---------- command loop ---------- */
static uint32_t rd32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }

static void handle_open(uint8_t *payload, uint32_t size)
{
    uint32_t fileBytes, folderChars, rate, loops;
    int32_t volume;
    wchar_t *folder;
    uint8_t *file;
    uint32_t reply[3];
    if (size < 20) fail("Invalid open request");
    fileBytes = rd32(payload); folderChars = rd32(payload + 4); rate = rd32(payload + 8);
    volume = (int32_t)rd32(payload + 12); loops = rd32(payload + 16);
    if (!folderChars || folderChars > 32767 || !fileBytes || fileBytes > MAX_FILE ||
        20ull + (uint64_t)folderChars * 2 + fileBytes != size) fail("Invalid file or folder size");
    if (volume < 0 || volume > 127) fail("Invalid synthesis settings");
    if (rate != 44100 && rate != 48000) fail("Unsupported sample rate %u", rate);
    folder = (wchar_t *)(payload + 20);
    file = (uint8_t *)malloc(fileBytes);
    if (!file) fail("Out of memory");
    memcpy(file, payload + 20 + folderChars * 2, fileBytes);
    if (!g_haveEngine || g_rate != rate || wcsncmp(g_folder, folder, folderChars) != 0 || g_folder[folderChars] != 0) {
        unload_libraries();
        memcpy(g_folder, folder, folderChars * sizeof(wchar_t));
        g_folder[folderChars] = 0;
        load_libraries(g_folder, rate);
    }
    open_song(file, fileBytes, volume, loops);
    reply[0] = g_length; reply[1] = g_rate; reply[2] = 2;
    send_msg(CMD_OPEN, reply, sizeof reply);
}

static void handle_read(const uint8_t *payload, uint32_t size)
{
    uint32_t blocks, n, frames = 0;
    int32_t tempo, transpose, volume;
    float *pcm;
    uint32_t eof;
    if (!g_handle || size != 16) fail("Invalid read request");
    tempo = (int32_t)rd32(payload); transpose = (int32_t)rd32(payload + 4);
    volume = (int32_t)rd32(payload + 8); blocks = rd32(payload + 12);
    if (blocks < 1 || blocks > MAX_BLOCKS) fail("Invalid block count");
    if (tempo != g_tempo || transpose != g_transpose || volume != g_volume) controls(tempo, transpose, volume);
    pcm = (float *)malloc(4 + (size_t)blocks * MAX_BLOCK_FRAMES * 8);
    if (!pcm) fail("Out of memory");
    for (n = 0; n < blocks; ++n) {
        unsigned got = render_block((float *)((uint8_t *)pcm + 4) + (size_t)frames * 2);
        if (!got) break;
        frames += got;
    }
    eof = g_finished ? 1u : 0u;
    memcpy(pcm, &eof, 4);
    send_msg(CMD_READ, pcm, 4 + frames * 8);
    free(pcm);
}

int main(void)
{
    HANDLE nul;
    g_in = GetStdHandle(STD_INPUT_HANDLE);
    g_out = GetStdHandle(STD_OUTPUT_HANDLE);
    /* Keep stdout exclusively for the protocol: nothing a DLL prints may reach it. */
    nul = CreateFileW(L"NUL", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if (nul != INVALID_HANDLE_VALUE) {
        SetStdHandle(STD_OUTPUT_HANDLE, nul);
        SetStdHandle(STD_ERROR_HANDLE, nul);
    }
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX);

    if (setjmp(g_fail)) {
        send_msg(CMD_ERROR, g_msg, (uint32_t)strlen(g_msg));
        TerminateProcess(GetCurrentProcess(), 4);
        return 4;
    }
    for (;;) {
        Header h;
        uint8_t *payload = NULL;
        if (!io_all(g_in, &h, sizeof h, 0)) break; /* parent closed the pipe */
        if (h.magic != MAGIC || h.version != VERSION || h.bytes > MAX_PAYLOAD) fail("Invalid request header");
        if (h.bytes) {
            payload = (uint8_t *)malloc(h.bytes);
            if (!payload) fail("Out of memory");
            if (!io_all(g_in, payload, h.bytes, 0)) break;
        }
        if (h.command == CMD_QUIT) { free(payload); break; }
        else if (h.command == CMD_OPEN) handle_open(payload, h.bytes);
        else if (h.command == CMD_READ) handle_read(payload, h.bytes);
        else if (h.command == CMD_SEEK) {
            uint64_t ms;
            if (!g_handle || h.bytes != 8) fail("Invalid seek request");
            memcpy(&ms, payload, 8);
            seek_song(ms);
            send_msg(CMD_SEEK, NULL, 0);
        } else fail("Unknown command %u", h.command);
        free(payload);
    }
    unload_libraries();
    return 0;
}
