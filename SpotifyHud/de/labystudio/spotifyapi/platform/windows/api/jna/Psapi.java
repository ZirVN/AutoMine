package de.labystudio.spotifyapi.platform.windows.api.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public interface Psapi extends WinNT, StdCallLibrary {
    public static final Psapi INSTANCE = (Psapi) Native.loadLibrary("psapi", Psapi.class, W32APIOptions.UNICODE_OPTIONS);

    public static class ModuleFilter {
        public static final int NONE = 0;
        public static final int X32BIT = 1;
        public static final int X64BIT = 2;
        public static final int ALL = 3;
    }

    boolean EnumProcessModulesEx(WinNT.HANDLE handle, Pointer[] pointerArr, int i, IntByReference intByReference, int i2);

    int GetModuleBaseName(WinNT.HANDLE handle, Pointer pointer, char[] cArr, int i);

    boolean GetModuleInformation(WinNT.HANDLE handle, Pointer pointer, ModuleInfo moduleInfo, int i);

    public static class ModuleInfo extends Structure {
        public Pointer BaseOfDll;
        public int SizeOfImage;
        public Pointer EntryPoint;

        public long getBaseOfDll() {
            return Pointer.nativeValue(this.BaseOfDll);
        }

        public long getEntryPoint() {
            return Pointer.nativeValue(this.EntryPoint);
        }

        public int getSizeOfImage() {
            return this.SizeOfImage;
        }

        protected List<String> getFieldOrder() {
            return new ArrayList(Arrays.asList("BaseOfDll", "SizeOfImage", "EntryPoint"));
        }
    }
}
