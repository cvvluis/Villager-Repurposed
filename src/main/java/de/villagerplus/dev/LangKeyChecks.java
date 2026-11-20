package de.villagerplus.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language-independent checks for the translatable texts: both language files carry the same keys with the same
 * placeholders, and every translation key referenced by the compiled mod classes (constant pool) exists in both.
 * When the sources are available (development run) the number of arguments passed to each
 * {@code Component.translatable("literal key", ...)} call is compared with the placeholders of that key as well.
 */
public final class LangKeyChecks {
	private static final String LANG_DIR = "/assets/villagerplus/lang/";
	private static final Pattern KEY = Pattern.compile("(?:message|gui|board|block|item|entity)\\.villagerplus\\.[a-z0-9_.]+");
	private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?([sd%])");
	private static final Pattern LITERAL_TEXT = Pattern.compile("Component\\.literal\\(\"([^\"]*)\"");
	/**
	 * Files whose remaining {@code Component.literal} words are deliberate: dev/test commands, and names or sign text that
	 * game logic compares as German strings (changing them would break existing worlds).
	 */
	private static final Set<String> LITERAL_ALLOWED = Set.of("StatusCommand.java", "Mining.java", "Caravan.java", "CaravanCampSecurity.java",
		"Guests.java", "RailTransport.java", "Network.java", "VillageMessages.java", "ClientChecks.java");
	private static int checks;

	private LangKeyChecks() { }

	public static int run() throws Exception {
		checks = 0;
		Map<String, String> de = load("de_de.json");
		Map<String, String> en = load("en_us.json");
		require(!de.isEmpty(), "de_de.json has entries");
		require(de.keySet().equals(en.keySet()), "de_de and en_us define identical key sets: only de " + minus(de.keySet(), en.keySet())
			+ ", only en " + minus(en.keySet(), de.keySet()));
		for (Map.Entry<String, String> entry : de.entrySet()) {
			String key = entry.getKey();
			String german = entry.getValue();
			String english = en.get(key);
			require(!german.isBlank() && !english.isBlank(), key + " has a non-empty text in both languages");
			require(placeholders(german).equals(placeholders(english)), key + " has matching placeholders (de " + placeholders(german) + ", en " + placeholders(english) + ")");
			require(!german.contains("§") && !english.contains("§"), key + " has no legacy formatting codes");
		}

		Set<String> used = usedKeys();
		require(used.size() > 100, "the compiled classes reference translation keys (found " + used.size() + ")");
		for (String key : used) {
			require(de.containsKey(key) && en.containsKey(key), "translation key used in code exists in both language files: " + key);
		}
		Set<String> unused = new TreeSet<>(de.keySet());
		unused.removeAll(used);
		unused.removeIf(key -> key.startsWith("block.") || key.startsWith("item.") || key.startsWith("entity."));
		require(unused.isEmpty(), "every message/gui/board key of the language files is used by the code, unused: " + unused);

		// The dev server runs in run/, the sources are one level up; without sources only this part is skipped.
		for (String candidate : new String[] {"src/main/java/de/villagerplus", "../src/main/java/de/villagerplus"}) {
			Path sources = Path.of(candidate);
			if (Files.isDirectory(sources)) { checkArgumentCounts(sources, de); break; }
		}
		return checks;
	}

	private static void checkArgumentCounts(Path root, Map<String, String> lang) throws IOException {
		int calls = 0;
		try (var files = Files.walk(root)) {
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				String source = Files.readString(file, StandardCharsets.UTF_8);
				int from = 0;
				while (true) {
					int at = source.indexOf("translatable(", from);
					if (at < 0) break;
					from = at + 1;
					List<String> args = splitArguments(source, at + "translatable(".length());
					if (args == null || args.isEmpty()) continue;
					Matcher keys = KEY.matcher(args.get(0));
					List<String> literalKeys = new ArrayList<>();
					while (keys.find()) literalKeys.add(keys.group());
					if (literalKeys.isEmpty() || args.get(0).contains("[")) continue;
					for (String key : literalKeys) {
						if (!lang.containsKey(key)) continue;
						calls++;
						int expected = placeholders(lang.get(key)).size();
						require(args.size() - 1 == expected, file.getFileName() + ": " + key + " is passed " + (args.size() - 1) + " argument(s), the text has " + expected + " placeholder(s)");
					}
				}
			}
		}
		require(calls > 100, "argument counts of translatable calls were compared (" + calls + ")");
		checkNoNewLiterals(root);
	}

	/** A player-facing text with real words must be a translation key; only allowlisted files may keep literal words. */
	private static void checkNoNewLiterals(Path root) throws IOException {
		Pattern word = Pattern.compile("[A-Za-zÄÖÜäöüß]{3,}");
		try (var files = Files.walk(root)) {
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				String name = file.getFileName().toString();
				if (name.contains("Checks") || LITERAL_ALLOWED.contains(name) || file.toString().contains("/dev/")) continue;
				Matcher m = LITERAL_TEXT.matcher(Files.readString(file, StandardCharsets.UTF_8));
				while (m.find()) require(!word.matcher(m.group(1)).find(), name + " has a hard-coded player text, use Component.translatable: \"" + m.group(1) + "\"");
			}
		}
		checks++;
	}

	/** Splits the argument list that starts right after an opening parenthesis; null if it cannot be parsed. */
	private static List<String> splitArguments(String s, int start) {
		List<String> out = new ArrayList<>();
		int depth = 0;
		int argStart = start;
		for (int i = start; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"') {
				i++;
				while (i < s.length() && s.charAt(i) != '"') i += s.charAt(i) == '\\' ? 2 : 1;
			} else if (c == '\'') {
				i++;
				while (i < s.length() && s.charAt(i) != '\'') i += s.charAt(i) == '\\' ? 2 : 1;
			} else if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				if (depth == 0) {
					String last = s.substring(argStart, i).trim();
					if (!last.isEmpty() || !out.isEmpty()) out.add(last);
					return out;
				}
				depth--;
			} else if (c == ',' && depth == 0) {
				out.add(s.substring(argStart, i).trim());
				argStart = i + 1;
			}
		}
		return null;
	}

	private static List<String> placeholders(String text) {
		List<String> found = new ArrayList<>();
		Matcher m = PLACEHOLDER.matcher(text);
		int next = 1;
		while (m.find()) {
			if (m.group(2).equals("%")) continue;
			found.add((m.group(1) == null ? Integer.toString(next++) : m.group(1)) + m.group(2));
		}
		found.sort(null);
		// A '%' that is not part of a placeholder or "%%" would make the client reject the translation.
		require(text.replaceAll("%(?:(\\d+)\\$)?[sd%]", "").indexOf('%') < 0, "stray percent sign in: " + text);
		return found;
	}

	private static Map<String, String> load(String name) throws IOException {
		try (InputStream in = LangKeyChecks.class.getResourceAsStream(LANG_DIR + name)) {
			require(in != null, name + " is on the classpath");
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> map = new TreeMap<>();
			for (Map.Entry<String, JsonElement> e : json.entrySet()) map.put(e.getKey(), e.getValue().getAsString());
			return map;
		}
	}

	private static Set<String> minus(Set<String> a, Set<String> b) {
		Set<String> r = new TreeSet<>(a);
		r.removeAll(b);
		return r;
	}

	/** All constant-pool strings of the mod's classes that look like a villagerplus translation key. */
	private static Set<String> usedKeys() throws IOException, URISyntaxException {
		Set<String> keys = new TreeSet<>();
		Path location = Path.of(LangKeyChecks.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		if (Files.isDirectory(location)) {
			try (var files = Files.walk(location)) {
				for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
					try (InputStream in = Files.newInputStream(file)) {
						collect(in, keys);
					}
				}
			}
		} else {
			try (JarFile jar = new JarFile(location.toFile())) {
				for (var entries = jar.entries(); entries.hasMoreElements();) {
					JarEntry entry = entries.nextElement();
					if (!entry.getName().endsWith(".class")) continue;
					try (InputStream in = jar.getInputStream(entry)) {
						collect(in, keys);
					}
				}
			}
		}
		return keys;
	}

	private static void collect(InputStream raw, Set<String> keys) throws IOException {
		DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
		in.readInt();
		in.readUnsignedShort();
		in.readUnsignedShort();
		int count = in.readUnsignedShort();
		for (int i = 1; i < count; i++) {
			int tag = in.readUnsignedByte();
			switch (tag) {
				case 1 -> {
					String value = in.readUTF();
					if (KEY.matcher(value).matches()) keys.add(value);
				}
				case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
				case 5, 6 -> { in.skipBytes(8); i++; }
				case 7, 8, 16, 19, 20 -> in.skipBytes(2);
				case 15 -> in.skipBytes(3);
				default -> throw new IOException("unknown constant pool tag " + tag);
			}
		}
	}

	private static void require(boolean condition, String message) {
		checks++;
		if (!condition) throw new IllegalStateException("Language key checks: " + message);
	}
}
