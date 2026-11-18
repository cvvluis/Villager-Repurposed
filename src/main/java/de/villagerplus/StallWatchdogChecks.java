package de.villagerplus;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dev check: a loop that stops beating gets its thread stacks written, a loop that keeps beating does not. */
public final class StallWatchdogChecks {
	private StallWatchdogChecks() { }

	public static int run() throws Exception {
		int checks = 0;
		Path dir = Files.createTempDirectory("vpstall");
		StallWatchdog silent = new StallWatchdog("test-silent", dir);
		silent.start();
		Thread.sleep(11_500L);
		silent.stop();
		Path report = dir.resolve("logs").resolve("villagerplus-stall-test-silent.txt");
		require(Files.exists(report), "a loop without heartbeat for 11 seconds is reported");
		checks++;
		String text = Files.readString(report);
		require(text.contains("stalled for") && text.contains("Server thread"), "the report names the stall and contains the server thread stack");
		checks++;

		StallWatchdog beating = new StallWatchdog("test-beating", dir);
		beating.start();
		for (int i = 0; i < 11; i++) {
			Thread.sleep(1_000L);
			beating.beat();
		}
		beating.stop();
		require(!Files.exists(dir.resolve("logs").resolve("villagerplus-stall-test-beating.txt")), "a loop that keeps beating is never reported");
		checks++;
		return checks;
	}

	private static void require(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("STALL WATCHDOG CHECK FAILED: " + message);
	}
}
