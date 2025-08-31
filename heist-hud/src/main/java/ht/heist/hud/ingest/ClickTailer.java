// ============================================================================
// FILE: ClickTailer.java
// PACKAGE: ht.heist.hud.ingest
// TITLE: ClickTailer — tails JSONL of synthetic taps and invokes a callback
//
// FORMAT (one JSON object per line):
//   {"x":123,"y":456,"ts":1712345678901}
//
// CALLBACK
// • TapConsumer.accept(x, y, ts) on the RuneLite client thread.
// • "~" in paths expands to user.home.
//
// THREADING
// • Dedicated daemon thread; safe stop() via interrupt flag.
// ============================================================================
package ht.heist.hud.ingest;

import net.runelite.client.callback.ClientThread;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClickTailer implements Runnable {

    // Functional callback for parsed taps
    public interface TapConsumer { void accept(int x, int y, long ts); }

    private final ClientThread clientThread;
    private final TapConsumer onTap;
    private final Path path;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public ClickTailer(ClientThread clientThread, String jsonlPath, TapConsumer onTap) {
        this.clientThread = clientThread;
        this.onTap = onTap;
        this.path = expandTilde(jsonlPath);
    }

    public void start() {
        if (running.getAndSet(true)) return;
        thread = new Thread(this, "Heist-ClickTailer");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            if (!Files.exists(path)) Files.createFile(path);
        } catch (IOException ignored) {}

        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // consume existing lines
            while (running.get() && br.readLine() != null) { /* seek to end */ }

            // tail new lines
            String line;
            while (running.get()) {
                line = br.readLine();
                if (line == null) {
                    try { Thread.sleep(150); } catch (InterruptedException e) { /* exit soon */ }
                    continue;
                }
                final int[] xyz = parseLine(line);
                if (xyz != null) {
                    final int x = xyz[0], y = xyz[1];
                    final long ts = ((long) xyz[2]) & 0xFFFFFFFFL; // we packed into int parse; recover
                    clientThread.invokeLater(() -> onTap.accept(x, y, ts));
                }
            }
        } catch (IOException ignored) { }
    }

    // extremely small parser to avoid pulling a JSON lib
    private static int[] parseLine(String s) {
        try {
            int xi = s.indexOf("\"x\":");
            int yi = s.indexOf("\"y\":");
            int ti = s.indexOf("\"ts\":");
            if (xi < 0 || yi < 0) return null;

            int xStart = xi + 4; int xEnd = xStart;
            while (xEnd < s.length() && Character.isDigit(s.charAt(xEnd))) xEnd++;
            int x = Integer.parseInt(s.substring(xStart, xEnd));

            int yStart = yi + 4; int yEnd = yStart;
            while (yEnd < s.length() && Character.isDigit(s.charAt(yEnd))) yEnd++;
            int y = Integer.parseInt(s.substring(yStart, yEnd));

            long ts = System.currentTimeMillis();
            if (ti >= 0) {
                int tStart = ti + 5; int tEnd = tStart;
                while (tEnd < s.length() && Character.isDigit(s.charAt(tEnd))) tEnd++;
                try { ts = Long.parseLong(s.substring(tStart, tEnd)); } catch (NumberFormatException ignore) { }
            }

            // return x, y, and lower 32 bits of ts (packed into int)
            return new int[]{x, y, (int)(ts & 0xFFFFFFFFL)};
        } catch (Exception e) {
            return null;
        }
    }

    private static Path expandTilde(String p) {
        if (p == null || p.isEmpty()) return Paths.get(System.getProperty("user.home"));
        if (p.equals("~")) return Paths.get(System.getProperty("user.home"));
        if (p.startsWith("~/") || p.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home"), p.substring(2));
        }
        return Paths.get(p);
    }
}
