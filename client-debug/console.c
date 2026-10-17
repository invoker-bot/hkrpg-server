/* SPDX-License-Identifier: AGPL-3.0-or-later
 * A client-owned console. System VERSION exports are forwarded by the build.
 * No game instructions, network packets, credentials or server logs are changed.
 */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0601
#include <windows.h>
#include <shlobj.h>
#include <knownfolders.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
#include <ctype.h>

static volatile LONG status;
static HANDLE output = INVALID_HANDLE_VALUE;
static HANDLE journal = INVALID_HANDLE_VALUE;

__declspec(dllexport) DWORD WINAPI HKRPGDebugConsoleStatus(void) {
    return (DWORD)InterlockedCompareExchange(&status, 0, 0);
}

static void emit(const char *line) {
    char text[9000];
    SYSTEMTIME now;
    GetLocalTime(&now);
    int length = snprintf(text, sizeof(text), "[%02u:%02u:%02u] %s\r\n",
        now.wHour, now.wMinute, now.wSecond, line);
    if (length <= 0) return;
    if (length >= (int)sizeof(text)) length = sizeof(text) - 1;
    DWORD written;
    if (output != INVALID_HANDLE_VALUE) WriteFile(output, text, length, &written, NULL);
    if (journal != INVALID_HANDLE_VALUE) WriteFile(journal, text, length, &written, NULL);
}

static BOOL WINAPI console_control(DWORD event) {
    /* Ctrl+C and Ctrl+Break must not terminate the game. */
    return event == CTRL_C_EVENT || event == CTRL_BREAK_EVENT;
}

static int sensitive(const char *line) {
    char lower[8192];
    size_t length = strlen(line);
    if (length >= sizeof(lower)) return 1;
    for (size_t i = 0; i <= length; ++i) lower[i] = (char)tolower((unsigned char)line[i]);
    const char *keys[] = {"token", "authkey", "password", "email", "account", "combo",
        "secret", "authorization", "cookie", "passport", "ticket", "session_key"};
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); ++i)
        if (strstr(lower, keys[i])) return 1;
    /* A URL query can contain credentials under an unrecognized field name. */
    if ((strstr(lower, "http://") || strstr(lower, "https://")) && strchr(lower, '?')) return 1;
    return 0;
}

typedef struct {
    ULONGLONG position;
    DWORD volume, index_high, index_low;
    unsigned char checkpoint[64];
    DWORD checkpoint_size;
    char pending[8192];
    size_t pending_size;
    int discard_line, initialized;
} LogCursor;

static void consume(LogCursor *cursor, const char *data, DWORD size) {
    for (DWORD i = 0; i < size; ++i) {
        char ch = data[i];
        if (ch == '\n') {
            cursor->pending[cursor->pending_size] = 0;
            if (!cursor->discard_line && cursor->pending_size && !sensitive(cursor->pending)) {
                char line[8300];
                snprintf(line, sizeof(line), "[Unity] %s", cursor->pending);
                emit(line);
            }
            cursor->pending_size = 0;
            cursor->discard_line = 0;
        } else if (ch != '\r') {
            if (cursor->pending_size + 1 >= sizeof(cursor->pending)) cursor->discard_line = 1;
            else if (!cursor->discard_line) cursor->pending[cursor->pending_size++] = ch;
        }
    }
}

static void seek(HANDLE file, ULONGLONG position) {
    LARGE_INTEGER offset;
    offset.QuadPart = (LONGLONG)position;
    SetFilePointerEx(file, offset, NULL, FILE_BEGIN);
}

static void tail(LogCursor *cursor, const wchar_t *path) {
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return;
    BY_HANDLE_FILE_INFORMATION info;
    if (!GetFileInformationByHandle(file, &info)) { CloseHandle(file); return; }
    ULONGLONG size = ((ULONGLONG)info.nFileSizeHigh << 32) | info.nFileSizeLow;
    int replaced = cursor->initialized && (cursor->volume != info.dwVolumeSerialNumber
        || cursor->index_high != info.nFileIndexHigh || cursor->index_low != info.nFileIndexLow
        || size < cursor->position);
    if (cursor->initialized && !replaced && cursor->checkpoint_size) {
        unsigned char checkpoint[64];
        DWORD read;
        seek(file, cursor->position - cursor->checkpoint_size);
        if (!ReadFile(file, checkpoint, cursor->checkpoint_size, &read, NULL)
                || read != cursor->checkpoint_size
                || memcmp(checkpoint, cursor->checkpoint, read)) replaced = 1;
    }
    if (!cursor->initialized) {
        cursor->position = size; /* Do not replay a previous game session. */
        cursor->initialized = 1;
    }
    if (replaced) {
        cursor->position = 0;
        cursor->pending_size = 0;
        cursor->discard_line = 0;
        emit("[Unity] New Player.log session");
    }
    cursor->volume = info.dwVolumeSerialNumber;
    cursor->index_high = info.nFileIndexHigh;
    cursor->index_low = info.nFileIndexLow;
    seek(file, cursor->position);
    char data[8192];
    DWORD read;
    /* Bound one poll so logging cannot monopolize a game process thread. */
    for (int batch = 0; batch < 16 && ReadFile(file, data, sizeof(data), &read, NULL) && read; ++batch) {
        consume(cursor, data, read);
        cursor->position += read;
    }
    cursor->checkpoint_size = cursor->position < 64 ? (DWORD)cursor->position : 64;
    seek(file, cursor->position - cursor->checkpoint_size);
    ReadFile(file, cursor->checkpoint, cursor->checkpoint_size, &read, NULL);
    if (read != cursor->checkpoint_size) cursor->checkpoint_size = 0;
    CloseHandle(file);
}

static DWORD WINAPI worker(void *unused) {
    (void)unused;
    HMODULE pinned_module;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_PIN,
        (LPCWSTR)(void *)worker, &pinned_module);
    if (!AllocConsole()) { InterlockedExchange(&status, 3); return 0; }
    SetConsoleTitleW(L"HKRPG Client Debug");
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCtrlHandler(console_control, TRUE);
    output = CreateFileW(L"CONOUT$", GENERIC_WRITE, FILE_SHARE_WRITE | FILE_SHARE_READ,
        NULL, OPEN_EXISTING, 0, NULL);
    wchar_t path[32768], log_path[32768];
    PWSTR folder = NULL;
    if (SUCCEEDED(SHGetKnownFolderPath(&FOLDERID_LocalAppData, 0, NULL, &folder))) {
        swprintf(path, 32768, L"%ls\\HKRPG", folder);
        CreateDirectoryW(path, NULL);
        SYSTEMTIME now; GetLocalTime(&now);
        swprintf(log_path, 32768, L"%ls\\client-debug-%04u%02u%02u-%02u%02u%02u-%lu.log",
            path, now.wYear, now.wMonth, now.wDay, now.wHour, now.wMinute, now.wSecond,
            GetCurrentProcessId());
        journal = CreateFileW(log_path, GENERIC_WRITE, FILE_SHARE_READ, NULL,
            CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
        CoTaskMemFree(folder);
    }
    char line[256];
    snprintf(line, sizeof(line), "HKRPG CLIENT DEBUG - client PID %lu", GetCurrentProcessId());
    emit(line);
    emit("Client-owned console. Source: Unity Player.log; sensitive lines are omitted.");
    emit("Ctrl+C is safe. This console exits with the game; closing the window may end the game.");
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_LocalAppDataLow, 0, NULL, &folder))) {
        emit("Cannot locate LocalAppDataLow for Player.log");
        InterlockedExchange(&status, 3);
        return 0;
    }
    swprintf(path, 32768, L"%ls\\miHoYo\\\u5d29\u574f\uff1a\u661f\u7a79\u94c1\u9053\\Player.log", folder);
    CoTaskMemFree(folder);
#ifdef HKRPG_CONSOLE_TEST
    /* Test-only build. The installed DLL never reads a log-path override. */
    GetEnvironmentVariableW(L"HKRPG_CONSOLE_TEST_LOG", path, 32768);
#endif
    LogCursor cursor = {0};
    tail(&cursor, path);
    /* If no file existed at startup, read the first newly created file from zero. */
    cursor.initialized = 1;
    InterlockedExchange(&status, 1);
    const wchar_t *modules[] = {L"UnityPlayer.dll", L"GameAssembly.dll", L"AccountPlatNative.dll", L"kcp.dll"};
    int loaded[4] = {0};
    for (;;) {
        for (int i = 0; i < 4; ++i) if (!loaded[i] && GetModuleHandleW(modules[i])) {
            loaded[i] = 1;
            snprintf(line, sizeof(line), "[Module] %ls loaded", modules[i]);
            emit(line);
        }
        tail(&cursor, path);
        Sleep(250);
    }
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, void *reserved) {
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(instance);
        wchar_t executable[32768];
        if (!GetModuleFileNameW(NULL, executable, 32768)) return TRUE;
        wchar_t *name = wcsrchr(executable, L'\\');
        if (_wcsicmp(name ? name + 1 : executable, L"StarRail.exe")) return TRUE;
        HANDLE thread = CreateThread(NULL, 0, worker, NULL, 0, NULL);
        if (thread) CloseHandle(thread);
        else InterlockedExchange(&status, 3);
    }
    return TRUE;
}
