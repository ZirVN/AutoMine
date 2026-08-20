package de.labystudio.spotifyapi.platform.windows.api;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import de.labystudio.spotifyapi.platform.windows.api.jna.Kernel32;
import de.labystudio.spotifyapi.platform.windows.api.jna.Psapi;
import de.labystudio.spotifyapi.platform.windows.api.jna.Tlhelp32;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public interface WinApi {
    public static final int PROCESS_VM_READ = 16;
    public static final int PROCESS_VM_WRITE = 32;
    public static final int PROCESS_VM_OPERATION = 8;
    public static final int VK_VOLUME_MUTE = 173;
    public static final int VK_VOLUME_DOWN = 174;
    public static final int VK_VOLUME_UP = 175;
    public static final int VK_MEDIA_NEXT_TRACK = 176;
    public static final int VK_MEDIA_PREV_TRACK = 177;
    public static final int VK_MEDIA_STOP = 178;
    public static final int VK_MEDIA_PLAY_PAUSE = 179;

    public interface WindowCondition {
        boolean test(WinDef.HWND hwnd);
    }

    default int getProcessIdByName(String exeName) {
        Kernel32 kernel = Kernel32.INSTANCE;
        WinNT.HANDLE snapshot = kernel.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0L));
        Tlhelp32.PROCESSENTRY32.ByReference processEntry = new Tlhelp32.PROCESSENTRY32.ByReference();
        while (kernel.Process32Next(snapshot, processEntry)) {
            if (Native.toString(processEntry.szExeFile).equals(exeName)) {
                int processId = processEntry.th32ProcessID.intValue();
                kernel.CloseHandle(snapshot);
                return processId;
            }
        }
        kernel.CloseHandle(snapshot);
        return -1;
    }

    default List<Integer> getProcessIdsByName(String exeName) {
        List<Integer> processIds = new ArrayList<>();
        Kernel32 kernel = Kernel32.INSTANCE;
        WinNT.HANDLE snapshot = kernel.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0L));
        Tlhelp32.PROCESSENTRY32.ByReference processEntry = new Tlhelp32.PROCESSENTRY32.ByReference();
        while (kernel.Process32Next(snapshot, processEntry)) {
            if (Native.toString(processEntry.szExeFile).equals(exeName)) {
                processIds.add(Integer.valueOf(processEntry.th32ProcessID.intValue()));
            }
        }
        kernel.CloseHandle(snapshot);
        return processIds;
    }

    default WinNT.HANDLE openProcessHandle(int processId) {
        Kernel32 kernel = Kernel32.INSTANCE;
        return kernel.OpenProcess(56, false, processId);
    }

    default WinDef.HWND openWindow(int processId) {
        return openWindow(processId, hWnd -> {
            return true;
        });
    }

    default WinDef.HWND openWindow(int processId, WindowCondition condition) {
        AtomicReference<WinDef.HWND> window = new AtomicReference<>();
        User32.INSTANCE.EnumWindows((hWnd, data) -> {
            IntByReference reference = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hWnd, reference);
            if (reference.getValue() == processId && isWindowVisible(hWnd) && condition.test(hWnd)) {
                window.set(hWnd);
                return false;
            }
            return true;
        }, (Pointer) null);
        return window.get();
    }

    default boolean isWindowVisible(WinDef.HWND hWnd) {
        return User32.INSTANCE.IsWindowVisible(hWnd);
    }

    default String getWindowTitle(WinDef.HWND window) {
        char[] buffer = new char[512];
        int length = User32.INSTANCE.GetWindowText(window, buffer, buffer.length);
        return Native.toString(Arrays.copyOf(buffer, length));
    }

    default Map<String, Psapi.ModuleInfo> getModules(WinNT.HANDLE handle) {
        Map<String, Psapi.ModuleInfo> modules = new HashMap<>();
        Pointer[] moduleHandles = getModuleHandles(handle);
        for (Pointer moduleHandle : moduleHandles) {
            char[] characters = new char[1024];
            int length = Psapi.INSTANCE.GetModuleBaseName(handle, moduleHandle, characters, characters.length);
            String moduleName = new String(characters, 0, length);
            Psapi.ModuleInfo moduleInfo = new Psapi.ModuleInfo();
            Psapi.INSTANCE.GetModuleInformation(handle, moduleHandle, moduleInfo, moduleInfo.size());
            modules.put(moduleName, moduleInfo);
        }
        return modules;
    }

    default Psapi.ModuleInfo getModuleInfo(WinNT.HANDLE handle, String moduleName) {
        Pointer[] moduleHandles = getModuleHandles(handle);
        for (Pointer moduleHandle : moduleHandles) {
            char[] characters = new char[1024];
            int length = Psapi.INSTANCE.GetModuleBaseName(handle, moduleHandle, characters, characters.length);
            String entryModuleName = new String(characters, 0, length);
            if (entryModuleName.equals(moduleName)) {
                Psapi.ModuleInfo moduleInfo = new Psapi.ModuleInfo();
                Psapi.INSTANCE.GetModuleInformation(handle, moduleHandle, moduleInfo, moduleInfo.size());
                return moduleInfo;
            }
        }
        return null;
    }

    default Pointer[] getModuleHandles(WinNT.HANDLE handle) {
        IntByReference amountRef = new IntByReference();
        Pointer[] moduleHandles = new Pointer[2048];
        if (!Psapi.INSTANCE.EnumProcessModulesEx(handle, moduleHandles, moduleHandles.length, amountRef, 3)) {
            throw new RuntimeException("Failed to get module list: ERROR " + Kernel32.INSTANCE.GetLastError());
        }
        int amount = amountRef.getValue();
        if (amount == 0) {
            throw new RuntimeException("No modules found");
        }
        return (Pointer[]) Arrays.copyOf(moduleHandles, amount);
    }

    default void pressKey(int keyCode) {
        WinUser.INPUT input = new WinUser.INPUT();
        input.type = new WinDef.DWORD(1L);
        input.input.setType("ki");
        input.input.ki.wVk = new WinDef.WORD(keyCode);
        input.input.ki.wScan = new WinDef.WORD(0L);
        input.input.ki.time = new WinDef.DWORD(0L);
        input.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0L);
        input.input.ki.dwFlags = new WinDef.DWORD(0L);
        User32.INSTANCE.SendInput(new WinDef.DWORD(1L), new WinUser.INPUT[]{input}, input.size());
        input.input.ki.dwFlags = new WinDef.DWORD(2L);
        User32.INSTANCE.SendInput(new WinDef.DWORD(1L), new WinUser.INPUT[]{input}, input.size());
    }
}
