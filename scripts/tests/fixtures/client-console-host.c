/* SPDX-License-Identifier: AGPL-3.0-or-later: local GUI host, never a game client. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

static DWORD WINAPI small_stack_thread(void *argument) {
    (void)argument;
    return 71;
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    (void)instance; (void)previous; (void)command; (void)show;
    SetErrorMode(SEM_NOGPFAULTERRORBOX | SEM_FAILCRITICALERRORS);
    wchar_t folder[32768], path[32768], system[32768];
    if (!GetEnvironmentVariableW(L"HKRPG_CONSOLE_HOST_DIR", folder, 32768)) return 10;
    GetSystemDirectoryW(system, 32768);
    swprintf(path, 32768, L"%ls\\kernel32.dll", system);
    DWORD unused;
    /* Static VERSION.dll import exercises the same load route as UnityPlayer. */
    if (!GetFileVersionInfoSizeW(path, &unused)) return 11;
    GetModuleFileNameW(NULL, path, 32768);
    wchar_t *slash = wcsrchr(path, L'\\');
    wcscpy(slash + 1, L"version.dll");
    HMODULE proxy = GetModuleHandleW(path);
    typedef DWORD (WINAPI *Status)(void);
    Status status = (Status)(void *)GetProcAddress(proxy, "HKRPGDebugConsoleStatus");
    if (!status) return 12;
    for (int i = 0; i < 150 && !status(); ++i) Sleep(100);
    if (status() != 1 || !GetConsoleWindow()) return 13;
    DWORD clients[16], count = GetConsoleProcessList(clients, 16);
    int owns = 0;
    for (DWORD i = 0; i < count && i < 16; ++i) if (clients[i] == GetCurrentProcessId()) owns = 1;
    wchar_t small[2];
    if (GetEnvironmentVariableW(L"HKRPG_CONSOLE_HOST_SMALL_STACK", small, 2)) {
        HANDLE thread = CreateThread(NULL, 65536, small_stack_thread, NULL,
            STACK_SIZE_PARAM_IS_A_RESERVATION, NULL);
        if (!thread || WaitForSingleObject(thread, 5000) != WAIT_OBJECT_0) return 15;
        DWORD exit_code;
        if (!GetExitCodeThread(thread, &exit_code) || exit_code != 71) return 16;
        CloseHandle(thread);
    }
    swprintf(path, 32768, L"%ls\\proof.json", folder);
    HANDLE proof = CreateFileW(path, GENERIC_WRITE, FILE_SHARE_READ, NULL, CREATE_NEW, 0, NULL);
    char json[256];
    int length = snprintf(json, sizeof(json), "{\"pid\":%lu,\"ownsConsole\":%s,\"consoleWindow\":%llu}\n",
        GetCurrentProcessId(), owns ? "true" : "false", (unsigned long long)(ULONG_PTR)GetConsoleWindow());
    DWORD written; WriteFile(proof, json, length, &written, NULL); CloseHandle(proof);
    swprintf(path, 32768, L"%ls\\stop", folder);
    for (int i = 0; i < 300; ++i) {
        if (GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES) return 0;
        Sleep(100);
    }
    return 14;
}
