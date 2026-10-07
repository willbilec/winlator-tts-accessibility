package com.winlator.winhandler;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Receives NVDA controller speech from Wine and plays it with Android TTS. */
public final class AndroidSpeechBridge implements AutoCloseable {
    private static final String TAG = "AndroidSpeechBridge";
    public static final int PORT = 51234;
    private final ServerSocket server;
    private final Thread serverThread;
    private final TextToSpeech tts;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float speechRate;
    private final String voiceName;
    private volatile boolean ready;
    private volatile boolean closed;

    public AndroidSpeechBridge(Context context) throws IOException {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String engine = preferences.getString("tts_engine", "");
        voiceName = preferences.getString("tts_voice", "");
        speechRate = Math.max(0.5f, Math.min(2.5f, preferences.getFloat("tts_rate", 1.0f)));
        server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
        TextToSpeech.OnInitListener listener = status -> mainHandler.post(() -> initializeVoice(status));
        tts = engine.isEmpty() ? new TextToSpeech(context.getApplicationContext(), listener)
                : new TextToSpeech(context.getApplicationContext(), listener, engine);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                try {
                    long delayMs = (SystemClock.elapsedRealtimeNanos() -
                            Long.parseLong(utteranceId)) / 1000000;
                    Log.d(TAG, "TTS playback start delay=" + delayMs + "ms");
                }
                catch (NumberFormatException ignored) {}
            }
            @Override public void onDone(String utteranceId) {}
            @Override public void onError(String utteranceId) {
                Log.w(TAG, "TTS playback error for utterance " + utteranceId);
            }
        });
        serverThread = new Thread(this::serve, "WinlatorNvdaSpeech");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void initializeVoice(int status) {
        if (closed) return;
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            tts.setSpeechRate(speechRate);
            if (!voiceName.isEmpty()) {
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice voice : voices) {
                        if (voice.getName().equals(voiceName)) {
                            tts.setVoice(voice);
                            break;
                        }
                    }
                }
            }
        }
        Log.i(TAG, "Android TTS init status=" + status);
    }

    private int speak(String text, int queueMode) {
        int maxLength = Math.max(1, Math.min(3900, TextToSpeech.getMaxSpeechInputLength() - 1));
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + maxLength);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) --end;
            String id = Long.toString(SystemClock.elapsedRealtimeNanos());
            if (tts.speak(text.substring(start, end), queueMode, null, id) != TextToSpeech.SUCCESS)
                return 1;
            start = end;
            queueMode = TextToSpeech.QUEUE_ADD;
        }
        return 0;
    }

    private void serve() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                int command = input.readUnsignedByte();
                int length = input.readInt();
                int result = 1;
                if (length >= 0 && length <= 65536) {
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    if (ready) {
                        if (command == 'P') result = 0;
                        else if (command == 'C') {
                            Log.d(TAG, "TTS cancel request");
                            result = tts.stop() == TextToSpeech.SUCCESS ? 0 : 1;
                        }
                        else if ((command == 'S' || command == 'A') && length > 0) {
                            String text = new String(bytes, StandardCharsets.UTF_8);
                            result = speak(text, command == 'S' ?
                                    TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD);
                        }
                    }
                }
                output.writeByte(result);
                output.flush();
            }
            catch (IOException exception) {
                if (!closed) Log.w(TAG, "Speech request failed", exception);
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ready = false;
        try { server.close(); } catch (IOException ignored) {}
        tts.stop();
        tts.shutdown();
    }
}
