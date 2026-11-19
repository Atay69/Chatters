package client;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Records a voice message from the microphone (as a WAV file in memory) and plays one back. */
final class VoiceRecorder {
    private static final AudioFormat FORMAT = new AudioFormat(16000.0F, 16, 1, true, false);

    private TargetDataLine microphone;
    private ByteArrayOutputStream recorded;
    private volatile boolean recording;

    boolean isRecording() {
        return recording;
    }

    /** Starts recording in a background thread. */
    void start() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("No microphone is available.");
        }
        microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(FORMAT);
        microphone.start();
        recorded = new ByteArrayOutputStream();
        recording = true;
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[4096];
            while (recording) {
                int count = microphone.read(buffer, 0, buffer.length);
                if (count > 0) {
                    recorded.write(buffer, 0, count);
                }
            }
        }, "voice-recorder");
        reader.setDaemon(true);
        reader.start();
    }

    /** Stops recording and returns the voice message as the bytes of a WAV file. */
    byte[] stop() throws IOException {
        recording = false;
        microphone.stop();
        microphone.close();
        byte[] raw = recorded.toByteArray();
        AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(raw), FORMAT,
                raw.length / FORMAT.getFrameSize());
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        AudioSystem.write(audio, AudioFileFormat.Type.WAVE, wav);
        return wav.toByteArray();
    }

    /** Plays a received voice message. */
    static void play(byte[] wav) throws Exception {
        Clip clip = AudioSystem.getClip();
        clip.open(AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav)));
        clip.start();
    }
}
