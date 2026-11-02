package com.crabot.voice;

// Host-only replay of production Kotlin/JNI. All clocks and audio files are fixed controls.
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;

public class DuplexClockProbe {
  static short[] read(String name) throws Exception {
    ShortBuffer data = ByteBuffer.wrap(Files.readAllBytes(Path.of(name))).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    short[] result = new short[data.remaining()]; data.get(result); return result;
  }
  static void append(ByteArrayOutputStream out, short[] pcm) {
    for (short sample : pcm) { out.write(sample & 255); out.write((sample >>> 8) & 255); }
  }
  static class Processor implements EchoProcessor {
    final NativeEchoProcessor nativeProcessor = new NativeEchoProcessor();
    final ByteArrayOutputStream reference = new ByteArrayOutputStream();
    final List<Long> nanos = new ArrayList<>();
    public void reset() { nativeProcessor.reset(); }
    public void render(short[] pcm) { append(reference, pcm); nativeProcessor.render(pcm); }
    public short[] capture(short[] pcm, int delay) {
      if (delay != 0) throw new AssertionError("streams must describe the same hardware interval");
      long before = System.nanoTime(); short[] result = nativeProcessor.capture(pcm, delay);
      nanos.add(System.nanoTime() - before); return result;
    }
    public void close() { nativeProcessor.close(); }
  }
  public static void main(String[] args) throws Exception {
    short[] logical = read(args[0]), capture = read(args[1]);
    Path output = Path.of(args[2]); String mode = args[3];
    boolean pause = !mode.equals("continuous"), wait = mode.equals("clock_wait"), jitter = mode.equals("jitter");
    long base = 1_000_000_000L; AtomicLong now = new AtomicLong(base);
    Processor processor = new Processor();
    List<Long> blocks = new ArrayList<>();
    ByteArrayOutputStream raw = new ByteArrayOutputStream(), clean = new ByteArrayOutputStream(), flags = new ByteArrayOutputStream();
    try (DuplexAudio audio = new DuplexAudio(processor, now::get);
         NativeSpeechDetector detector = new NativeSpeechDetector(args[4])) {
      audio.startCapture(); PlaybackReference reference = audio.startPlayback();
      int written = 0, lastMs = capture.length / 16;
      for (int ms = 0; ms < lastMs; ms += 20) {
        long hardwareTime = base + ms * 1_000_000L;
        now.set(hardwareTime + (jitter ? (ms % 100) * 1_000_000L : 0));
        boolean paused = pause && ms >= 12000 && ms < 12300;
        int played = (ms - (pause ? Math.min(Math.max(ms - 12000, 0), 300) : 0)) * 24;
        if (pause && ms == 12000) reference.paused(played);
        if (pause && ms == 12300) reference.resumed();
        boolean stampAvailable = !paused && !(wait && ms >= 12300 && ms < 12400);
        AudioStamp stamp = stampAvailable ? new AudioStamp(played, hardwareTime) : null;
        if (stamp != null) reference.clock(stamp);
        if (!paused) {
          int target = Math.min(logical.length, played + 1920);
          while (written < target) {
            int count = Math.min(73, target - written);
            reference.written(logical, written, count, written, stamp); written += count;
          }
        }
        int position = ms * 16;
        short[] frame = Arrays.copyOfRange(capture, position, position + 320);
        long before = System.nanoTime();
        audio.capture(new CapturedAudio(frame, position, new AudioStamp(position, hardwareTime)), (original, cleaned) -> {
          append(raw, original.getSamples()); append(clean, cleaned);
          SpeechActivity activity = detector.feed(cleaned);
          flags.write((activity.getCandidate() ? 1 : 0) | (activity.getConfirmed() ? 2 : 0));
          return Unit.INSTANCE;
        });
        blocks.add(System.nanoTime() - before);
      }
      reference.closed((long) logical.length);
      if (raw.size() != capture.length * 2 || processor.reference.size() != capture.length * 3)
        throw new AssertionError("capture or render timeline skipped/duplicated samples");
      ByteArrayOutputStream expected = new ByteArrayOutputStream(); append(expected, capture);
      if (!Arrays.equals(expected.toByteArray(), raw.toByteArray())) throw new AssertionError("identity PCM changed");
      Files.write(output.resolve("clean.pcm"), clean.toByteArray());
      Files.write(output.resolve("render.pcm"), processor.reference.toByteArray());
      Files.write(output.resolve("activity.bin"), flags.toByteArray());
      processor.nanos.sort(Long::compareTo);
      long firstBlock = blocks.get(0); blocks.sort(Long::compareTo);
      System.out.println("{\"frames\":" + processor.nanos.size() + ",\"capture_p95_us\":" + processor.nanos.get((int)(processor.nanos.size() * .95)) / 1000.0
          + ",\"timeline_apm_vad_20ms_p95_us\":" + blocks.get((int)(blocks.size() * .95)) / 1000.0
          + ",\"first_block_us\":" + firstBlock / 1000.0 + "}");
    }
  }
}
