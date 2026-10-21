package com.candytek.recorder.storage;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sidecar "<recording>.meta" file: the exact capture parameters used for a recording
 * (engine, source, device, effects…) which cannot be recovered from the audio file itself.
 */
public final class MetaFile {

	private MetaFile() {}

	public static File of(File recording) { return new File(recording.getPath() + ".meta"); }

	public static void write(File recording, Map<String, String> values) {
		File f = of(recording);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, String> e : values.entrySet()) {
				w.write(e.getKey().replace('=', '-').replace('\n', ' '));
				w.write('=');
				w.write(e.getValue() == null ? "" : e.getValue().replace('\n', ' '));
				w.write('\n');
			}
		} catch (IOException ignored) {
			// metadata is optional
		}
	}

	@NonNull
	public static Map<String, String> read(File recording) {
		Map<String, String> out = new LinkedHashMap<>();
		File f = of(recording);
		if (!f.isFile()) return out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				int eq = line.indexOf('=');
				if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
			}
		} catch (IOException ignored) {
		}
		return out;
	}
}
