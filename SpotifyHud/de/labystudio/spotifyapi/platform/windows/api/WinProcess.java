package de.labystudio.spotifyapi.platform.windows.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import de.labystudio.spotifyapi.platform.windows.api.jna.Kernel32;
import de.labystudio.spotifyapi.platform.windows.api.jna.Psapi;
import java.util.Map;

public class WinProcess implements WinApi {
    protected static final Gson GSON = new Gson();
    protected final int processId;
    protected final WinNT.HANDLE handle;
    protected final WinDef.HWND window;
    protected long scanTimeout = 10000;

    public interface SearchCondition {
        boolean matches(long j, int i);
    }

    public WinProcess(String executableName) {
        this.processId = getProcessIdByName(executableName);
        if (this.processId == -1) {
            throw new IllegalStateException("Process of executable " + executableName + " not found");
        }
        this.handle = openProcessHandle(this.processId);
        if (this.handle == null) {
            throw new IllegalStateException("Process handle of " + this.processId + " not found");
        }
        this.window = openWindow(this.processId, hWnd -> {
            String title = getWindowTitle(hWnd);
            return (title.equals("Spotify Debug Window") || title.equals("DevTools")) ? false : true;
        });
        if (getWindowTitle().isEmpty()) {
            throw new IllegalStateException("Window for process " + this.processId + " not found");
        }
    }

    public boolean readBoolean(long address) {
        return readByte(address) == 1;
    }

    public byte readByte(long address) {
        return readBytes(address, 1)[0];
    }

    public int readInteger(long address) {
        byte[] bytes = readBytes(address, 4);
        return (bytes[0] & 255) | ((bytes[1] & 255) << 8) | ((bytes[2] & 255) << 16) | ((bytes[3] & 255) << 24);
    }

    public String readString(long address, int length) {
        return new String(readBytes(address, length));
    }

    public byte[] readBytes(long address, int length) {
        Kernel32 kernel = Kernel32.INSTANCE;
        Memory memory = new Memory(length);
        kernel.ReadProcessMemory(this.handle, new Pointer(address), memory, length, new IntByReference(length));
        return memory.getByteArray(0L, length);
    }

    public long findInMemory(long minAddress, long maxAddress, byte[] searchBytes) {
        long timeStart = System.currentTimeMillis();
        long j = minAddress;
        while (true) {
            long cursor = j;
            if (cursor < maxAddress) {
                byte[] chunk = readBytes(cursor, 65536 + searchBytes.length);
                for (int i = 0; i < chunk.length - searchBytes.length; i++) {
                    boolean found = true;
                    int k = 0;
                    while (true) {
                        if (k >= searchBytes.length) {
                            break;
                        }
                        if (chunk[i + k] == searchBytes[k]) {
                            k++;
                        } else {
                            found = false;
                            break;
                        }
                    }
                    if (found) {
                        return cursor + i;
                    }
                }
                long timePassed = System.currentTimeMillis() - timeStart;
                if (timePassed <= this.scanTimeout) {
                    j = cursor + 65536;
                } else {
                    throw new IllegalStateException("Scan timeout of " + this.scanTimeout + "ms reached at address " + cursor);
                }
            } else {
                return -1L;
            }
        }
    }

    public long findInMemory(long minAddress, long maxAddress, byte[] searchBytes, SearchCondition condition) {
        long cursor = minAddress;
        int index = 0;
        while (cursor < maxAddress) {
            long target = findInMemory(cursor, maxAddress, searchBytes);
            if (target == -1 || condition.matches(target, index)) {
                return target;
            }
            cursor = target + 1;
            index++;
        }
        return -1L;
    }

    public boolean hasBytes(long address, int... bytes) {
        byte[] chunk = readBytes(address, bytes.length);
        for (int i = 0; i < chunk.length; i++) {
            if (chunk[i] != ((byte) bytes[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean hasBytes(long address, byte[] bytes) {
        byte[] chunk = readBytes(address, bytes.length);
        for (int i = 0; i < chunk.length; i++) {
            if (chunk[i] != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean hasBytes(long address, byte[]... chunksOfBytes) {
        for (byte[] bytes : chunksOfBytes) {
            if (hasBytes(address, bytes)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasText(long address, String text) {
        return hasBytes(address, text.getBytes());
    }

    public boolean hasText(long address, String... texts) {
        for (String text : texts) {
            if (hasText(address, text)) {
                return true;
            }
        }
        return false;
    }

    public long findAddressOfTextInModule(String moduleName, String text) {
        Psapi.ModuleInfo moduleInfo = getModuleInfo(moduleName);
        if (moduleInfo == null) {
            return -1L;
        }
        return findAddressOfText(moduleInfo.getBaseOfDll(), text, 0);
    }

    public long findAddressOfText(long start, String text, int index) {
        return findAddressOfText(start, text, (address, matchIndex) -> {
            return matchIndex == index;
        });
    }

    public long findAddressOfText(long start, String text, SearchCondition condition) {
        return findAddressOfText(start, Long.MAX_VALUE, text, condition);
    }

    public long findAddressOfText(long start, long end, String text, SearchCondition condition) {
        return findInMemory(start, end, text.getBytes(), condition);
    }

    public long findAddressOfTexts(long start, long end, SearchCondition condition, String... texts) {
        for (String text : texts) {
            long address = findAddressOfText(start, end, text, condition);
            if (address != -1) {
                return address;
            }
        }
        return -1L;
    }

    public long findAddressUsingPath(String... path) {
        long cursor = -1;
        for (String part : path) {
            cursor = findAddressOfText(cursor + 1, part, 0);
            if (cursor == -1) {
                return -1L;
            }
        }
        return cursor;
    }

    public long findAddressUsingRules(SearchRule... rules) {
        long cursor = -1;
        for (SearchRule rule : rules) {
            cursor = findAddressOfText(cursor + 1, rule.getText(), rule.getCondition());
            if (cursor == -1) {
                return -1L;
            }
        }
        return cursor;
    }

    public JsonObject readJsonObject(long address) {
        int depth = 0;
        boolean inString = false;
        long offset = 0;
        StringBuilder json = new StringBuilder();
        do {
            String chunk = readString(address + offset, 1024);
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (c == '\"') {
                    inString = !inString;
                }
                if (!inString) {
                    if (c == '{' || c == '[') {
                        depth++;
                    } else if (c == '}' || c == ']') {
                        depth--;
                    }
                }
                json.append(c);
                if (depth == 0) {
                    break;
                }
            }
            offset += 1024;
        } while (depth > 0);
        return (JsonObject) GSON.fromJson(json.toString(), JsonObject.class);
    }

    public Psapi.ModuleInfo getModuleInfo(String moduleName) {
        return getModuleInfo(this.handle, moduleName);
    }

    public Map<String, Psapi.ModuleInfo> getModules() {
        return getModules(this.handle);
    }

    public long getFirstModuleAddress() {
        long minAddress = Long.MAX_VALUE;
        for (Map.Entry<String, Psapi.ModuleInfo> module : getModules().entrySet()) {
            long baseOfDll = module.getValue().getBaseOfDll();
            if (baseOfDll > 0) {
                minAddress = Math.min(minAddress, baseOfDll);
            }
        }
        return minAddress;
    }

    public long getMaxProcessAddress() {
        long maxAddress = 0;
        for (Map.Entry<String, Psapi.ModuleInfo> module : getModules().entrySet()) {
            maxAddress = Math.max(maxAddress, module.getValue().getBaseOfDll() + module.getValue().getSizeOfImage());
        }
        return maxAddress;
    }

    public String getWindowTitle() {
        return getWindowTitle(this.window);
    }

    public int getProcessId() {
        return this.processId;
    }

    public WinNT.HANDLE getHandle() {
        return this.handle;
    }

    public void setScanTimeout(long scanTimeout) {
        this.scanTimeout = scanTimeout;
    }

    public boolean isOpen() {
        return this.handle != null;
    }

    public void close() {
        Kernel32.INSTANCE.CloseHandle(this.handle);
    }

    public static class SearchRule {
        private final String text;
        private final SearchCondition condition;

        public SearchRule(String text, SearchCondition condition) {
            this.text = text;
            this.condition = condition;
        }

        public String getText() {
            return this.text;
        }

        public SearchCondition getCondition() {
            return this.condition;
        }
    }
}
