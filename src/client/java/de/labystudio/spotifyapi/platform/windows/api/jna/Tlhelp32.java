package de.labystudio.spotifyapi.platform.windows.api.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinDef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public interface Tlhelp32 {
    public static final WinDef.DWORD TH32CS_SNAPMODULE = new WinDef.DWORD(8);
    public static final WinDef.DWORD TH32CS_SNAPPROCESS = new WinDef.DWORD(2);

    public static class MODULEENTRY32W extends Structure {
        public WinDef.DWORD dwSize;
        public WinDef.DWORD th32ModuleID;
        public WinDef.DWORD th32ProcessID;
        public WinDef.DWORD GlblcntUsage;
        public WinDef.DWORD ProccntUsage;
        public Pointer modBaseAddr;
        public WinDef.DWORD modBaseSize;
        public WinDef.HMODULE hModule;
        public char[] szModule;
        public char[] szExePath;

        public static class ByReference extends MODULEENTRY32W implements Structure.ByReference {
        }

        public MODULEENTRY32W() {
            this.szModule = new char[256];
            this.szExePath = new char[260];
            this.dwSize = new WinDef.DWORD(size());
        }

        public MODULEENTRY32W(Pointer memory) {
            super(memory);
            this.szModule = new char[256];
            this.szExePath = new char[260];
            read();
        }

        protected List<String> getFieldOrder() {
            return new ArrayList(Arrays.asList("dwSize", "th32ModuleID", "th32ProcessID", "GlblcntUsage", "ProccntUsage", "modBaseAddr", "modBaseSize", "hModule", "szModule", "szExePath"));
        }
    }

    public static class PROCESSENTRY32 extends Structure {
        public WinDef.DWORD dwSize;
        public WinDef.DWORD cntUsage;
        public WinDef.DWORD th32ProcessID;
        public BaseTSD.ULONG_PTR th32DefaultHeapID;
        public WinDef.DWORD th32ModuleID;
        public WinDef.DWORD cntThreads;
        public WinDef.DWORD th32ParentProcessID;
        public WinDef.LONG pcPriClassBase;
        public WinDef.DWORD dwFlags;
        public char[] szExeFile;

        public PROCESSENTRY32() {
            this.szExeFile = new char[260];
            this.dwSize = new WinDef.DWORD(size());
        }

        public PROCESSENTRY32(Pointer memory) {
            super(memory);
            this.szExeFile = new char[260];
            read();
        }

        public static class ByReference extends Tlhelp32.PROCESSENTRY32 implements Structure.ByReference {
            public ByReference() {
            }

            public ByReference(Pointer memory) {
                super(memory);
            }

            protected List<String> getFieldOrder() {
                return new ArrayList(Arrays.asList("dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID", "th32ModuleID", "cntThreads", "th32ParentProcessID", "pcPriClassBase", "dwFlags", "szExeFile"));
            }
        }

        protected List<String> getFieldOrder() {
            return new ArrayList(Arrays.asList("dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID", "th32ModuleID", "cntThreads", "th32ParentProcessID", "pcPriClassBase", "dwFlags", "szExeFile"));
        }
    }
}
