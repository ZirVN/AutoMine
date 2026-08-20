package com.automine.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Nguồn nhạc thứ hai: Windows Media Session (GSMTC) — nơi MỌI trình phát trên
 * Windows đăng ký bài đang chạy, kể cả Spotify WEB trong Chrome/Edge (thư viện
 * gốc chỉ soi được app Spotify desktop, nên "bật trên web không hoạt động").
 *
 * <p>Cách lấy không cần DLL ngoài: một daemon PowerShell chạy ẩn, hỏi
 * GlobalSystemMediaTransportControlsSessionManager mỗi giây và in một dòng
 * JSON {@code ##{"title":...,"artist":...,"status":...,"pos":...,"dur":...}}
 * cho Java đọc. Một tiến trình duy nhất, tự chết theo game.
 */
public final class WebMediaSession {

	/** Nhận dữ liệu bài hát; gọi từ luồng nền — bên nhận tự lo thread-safety. */
	public interface Sink {
		void update(String title, String artist, boolean playing, long positionMs, long durationMs, String app);

		void clear();
	}

	private Process process;
	private volatile boolean stopped;

	public synchronized void start(Sink sink) {
		if (process != null) {
			return;
		}
		try {
			Path script = FabricLoader.getInstance().getConfigDir().resolve("automine-media.ps1");
			Files.write(script, SCRIPT.getBytes(StandardCharsets.UTF_8));
			process = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
					"-WindowStyle", "Hidden", "-File", script.toString())
					.redirectErrorStream(true)
					.start();
			Thread reader = new Thread(() -> pump(sink), "AutoMine-MediaSession");
			reader.setDaemon(true);
			reader.start();
		} catch (Throwable t) {
			process = null;
		}
	}

	private void pump(Sink sink) {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (!stopped && (line = in.readLine()) != null) {
				if (!line.startsWith("##")) {
					continue; // tạp âm của PowerShell
				}
				try {
					JsonObject o = JsonParser.parseString(line.substring(2)).getAsJsonObject();
					if (!o.has("title") || o.get("title").isJsonNull()) {
						sink.clear();
						continue;
					}
					String title = o.get("title").getAsString();
					String artist = o.has("artist") && !o.get("artist").isJsonNull()
							? o.get("artist").getAsString() : "";
					String status = o.has("status") ? o.get("status").getAsString() : "";
					long pos = o.has("pos") ? o.get("pos").getAsLong() : 0;
					long dur = o.has("dur") ? o.get("dur").getAsLong() : 0;
					String app = o.has("app") && !o.get("app").isJsonNull()
							? o.get("app").getAsString() : "";
					sink.update(title, artist, "Playing".equalsIgnoreCase(status), pos, dur, app);
				} catch (Throwable ignored) {
					// một dòng hỏng không đáng để sập cả nguồn nhạc
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public synchronized void stop() {
		stopped = true;
		if (process != null) {
			process.destroy();
			process = null;
		}
	}

	/** Vòng hỏi GSMTC — mẫu AsTask kinh điển để await WinRT trong PowerShell 5.1. */
	private static final String SCRIPT = String.join("\r\n",
			"$ErrorActionPreference = 'SilentlyContinue'",
			// Không có assembly này thì [System.WindowsRuntimeSystemExtensions] không
			// tồn tại -> AsTask null -> cả daemon câm lặng. Đã dính thật, đừng bỏ.
			"Add-Type -AssemblyName System.Runtime.WindowsRuntime",
			// Java đọc stdout bằng UTF-8; mặc định PS 5.1 xả codepage OEM -> tên bài
			// tiếng Việt ('Mất Kết Nối') sẽ thành ký tự rác nếu thiếu dòng này.
			"[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
			"[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime] | Out-Null",
			"$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
			"function Await($op, $type) {",
			"  $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))",
			"  if ($task.Wait(2000)) { return $task.Result }",
			"  return $null",
			"}",
			"while ($true) {",
			"  $line = '##{}'",
			"  try {",
			"    $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
			"    if ($mgr) {",
			"      $s = $mgr.GetCurrentSession()",
			"      if ($s) {",
			"        $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
			"        $tl = $s.GetTimelineProperties()",
			"        $info = $s.GetPlaybackInfo()",
			"        if ($p) {",
			"          $o = @{ title = $p.Title; artist = $p.Artist; status = [string]$info.PlaybackStatus; pos = [int64]$tl.Position.TotalMilliseconds; dur = [int64]$tl.EndTime.TotalMilliseconds; app = $s.SourceAppUserModelId }",
			"          $line = '##' + (ConvertTo-Json $o -Compress)",
			"        }",
			"      }",
			"    }",
			"  } catch {}",
			"  Write-Output $line",
			"  Start-Sleep -Milliseconds 1000",
			"}");
}
