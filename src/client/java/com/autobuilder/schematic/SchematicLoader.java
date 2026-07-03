package com.autobuilder.schematic;

import java.io.IOException;
import java.nio.file.Path;

/** Picks the right format reader based on file extension. */
public final class SchematicLoader {

	private SchematicLoader() {
	}

	public static SchematicData load(Path path) throws IOException {
		String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);

		if (fileName.endsWith(".litematic")) {
			return LitematicaReader.read(path);
		}
		if (fileName.endsWith(".schem") || fileName.endsWith(".schematic")) {
			return SpongeSchematicReader.read(path);
		}

		throw new IOException("Unsupported schematic format: " + path.getFileName());
	}
}
