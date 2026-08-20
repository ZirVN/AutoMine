package de.labystudio.spotifyapi.platform.windows.api.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import de.labystudio.spotifyapi.platform.windows.api.jna.Tlhelp32;

public interface Kernel32 extends WinNT, StdCallLibrary {
    public static final Kernel32 INSTANCE = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

    boolean ReadProcessMemory(WinNT.HANDLE handle, Pointer pointer, Pointer pointer2, int i, IntByReference intByReference);

    boolean Module32NextW(WinNT.HANDLE handle, Tlhelp32.MODULEENTRY32W moduleentry32w);

    boolean Process32First(WinNT.HANDLE handle, Tlhelp32.PROCESSENTRY32.ByReference byReference);

    boolean Process32Next(WinNT.HANDLE handle, Tlhelp32.PROCESSENTRY32.ByReference byReference);

    WinNT.HANDLE CreateToolhelp32Snapshot(WinDef.DWORD dword, WinDef.DWORD dword2);

    boolean CloseHandle(WinNT.HANDLE handle);

    WinNT.HANDLE OpenProcess(int i, boolean z, int i2);

    int GetLastError();
}
