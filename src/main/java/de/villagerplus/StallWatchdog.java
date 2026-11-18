package de.villagerplus;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic only: when the server or client main loop stops ticking for several seconds, the stacks of all threads are
 * written to {@code logs/villagerplus-stall-<label>.txt} so a freeze can be traced to the code that holds it (this mod or
 * another one). It never interferes with the loop it watches and stops dumping after a few reports.
 */
public final class StallWatchdog {
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	private static final long SERVER_STALL_MILLIS = 8_000L;
	/** A few seconds without a frame is the freeze players see; a single hitch must not count. */
	private static final long CLIENT_FRAME_STALL_MILLIS = 3_000L;
	private static final int MAX_REPORTS = 3;
	private static volatile StallWatchdog clientWatch;

	private final String label;
	/** The loop only counts as stalled while this is true (the client loop does not tick while a world is being created or loaded). */
	private final java.util.function.BooleanSupplier armed;
	private final long stallMillis;
	private final Path logDirectory;
	private final AtomicLong lastTick = new AtomicLong(System.nanoTime());
	private volatile boolean running;
	private int reports;

	public StallWatchdog(String label, Path gameDirectory) {
		this(label, gameDirectory, () -> true, SERVER_STALL_MILLIS);
	}

	public StallWatchdog(String label, Path gameDirectory, java.util.function.BooleanSupplier armed) {
		this(label, gameDirectory, armed, SERVER_STALL_MILLIS);
	}

	public StallWatchdog(String label, Path gameDirectory, java.util.function.BooleanSupplier armed, long stallMillis) {
		this.armed = armed;
		this.label = label;
		this.stallMillis = stallMillis;
		this.logDirectory = gameDirectory.resolve("logs");
	}

	/** The client watchdog is beaten from the render thread, once per frame, not from the game tick. */
	public static void attachClient(StallWatchdog watchdog) {
		clientWatch = watchdog;
	}

	public static void beatClientFrame() {
		StallWatchdog watchdog = clientWatch;
		if (watchdog == null) return;
		watchdog.start();
		watchdog.beat();
	}

	/** Called from the watched loop once per tick. */
	public void beat() {
		lastTick.set(System.nanoTime());
	}

	public void start() {
		if (running) return;
		running = true;
		lastTick.set(System.nanoTime());
		Thread thread = new Thread(this::watch, "villagerplus-stall-watchdog-" + label);
		thread.setDaemon(true);
		thread.start();
	}

	public void stop() {
		running = false;
	}

	private void watch() {
		boolean reported = false;
		while (running) {
			try {
				Thread.sleep(2_000L);
			} catch (InterruptedException interrupted) {
				return;
			}
			boolean isArmed;
			try {
				isArmed = armed.getAsBoolean();
			} catch (Throwable error) {
				isArmed = false;
			}
			if (!isArmed) {
				lastTick.set(System.nanoTime());
				reported = false;
				continue;
			}
			long stalledMillis = (System.nanoTime() - lastTick.get()) / 1_000_000L;
			if (stalledMillis < stallMillis) {
				reported = false;
				continue;
			}
			if (pausedServerThread()) {
				// A menu pause must not be stored up and reported as soon as the world resumes.
				lastTick.set(System.nanoTime());
				reported = false;
				continue;
			}
			if (reported || reports >= MAX_REPORTS) continue;
			reported = true;
			reports++;
			try {
				dump(stalledMillis);
			} catch (Throwable error) {
				LOGGER.warn("Villagers: Repurposed: Haenger-Bericht ({}) fehlgeschlagen", label, error);
			}
		}
	}

	/**
	 * A paused single-player server parks the server thread in {@code waitUntilNextTick}. Chunk loads use the same
	 * {@code managedBlock}/{@code pollTask} frames, so those names alone must not count as a pause.
	 */
	private boolean pausedServerThread() {
		if (!label.equals("server")) return false;
		for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
			if (!entry.getKey().getName().equals("Server thread")) continue;
			return idleServerPause(entry.getKey().getState(), entry.getValue());
		}
		return false;
	}

	static boolean idleServerPause(Thread.State state, StackTraceElement[] stack) {
		if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) return false;
		if (stack == null) return false;
		boolean idleWait = false;
		for (StackTraceElement frame : stack) {
			String method = frame.getMethodName();
			if (method == null) continue;
			if (blocksOnChunk(method)) return false;
			if (method.equals("waitUntilNextTick")) idleWait = true;
		}
		return idleWait;
	}

	private static boolean blocksOnChunk(String method) {
		return method.contains("getChunk") || method.contains("Chunk") && (method.contains("Block") || method.contains("Generat"))
			|| method.contains("generate") || method.contains("Generate");
	}

	private void dump(long stalledMillis) throws IOException {
		Files.createDirectories(logDirectory);
		Path file = logDirectory.resolve("villagerplus-stall-" + label + ".txt");
		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
			out.println("=== " + LocalDateTime.now() + " " + label + " loop stalled for " + stalledMillis + " ms (report " + reports + ") ===");
			for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
				Thread thread = entry.getKey();
				String name = thread.getName();
				boolean interesting = name.contains("Server thread") || name.contains("Render thread") || name.contains("main")
					|| name.contains("Worker") || name.contains("c2me") || name.contains("Voxy");
				if (!interesting) continue;
				out.println("--- " + name + " (" + thread.getState() + ")");
				StackTraceElement[] frames = entry.getValue();
				for (int i = 0; i < Math.min(frames.length, 45); i++) out.println("    at " + frames[i]);
			}
			out.println();
		}
		LOGGER.warn("Villagers: Repurposed: {}-Schleife steht seit {} ms; Thread-Stapel in {}", label, stalledMillis, file);
	}
}
