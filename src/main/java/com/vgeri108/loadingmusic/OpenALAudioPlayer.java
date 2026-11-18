package com.vgeri108.loadingmusic;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OpenALAudioPlayer {

    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean(false);

    private static volatile Thread worker;

    private OpenALAudioPlayer() {
    }

    public static synchronized void start() {
        if (worker != null && worker.isAlive()) {
            return;
        }

        STOP_REQUESTED.set(false);

        worker = new Thread(OpenALAudioPlayer::play, "LoadingMusic-Audio");
        worker.setDaemon(true);
        worker.start();
    }

    public static void stop() {
        STOP_REQUESTED.set(true);

        Thread thread = worker;

        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();

            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void play() {
        long device = 0;
        long context = 0;
        boolean threadLocalContext = false;

        int buffer = 0;
        int source = 0;

        ByteBuffer encoded = null;
        ShortBuffer pcm = null;

        try {
            // Load OGG from the mod JAR.
            byte[] fileData;

            try (InputStream stream =
                         OpenALAudioPlayer.class.getResourceAsStream(
                                 "/assets/loadingmusic/sounds/music.ogg")) {

                if (stream == null) {
                    throw new IllegalStateException("music.ogg not found in mod resources");
                }

                fileData = stream.readAllBytes();
            }

            encoded = MemoryUtil.memAlloc(fileData.length);
            encoded.put(fileData).flip();

            // Decode OGG to PCM.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channelsBuffer = stack.mallocInt(1);
                IntBuffer sampleRateBuffer = stack.mallocInt(1);

                pcm = STBVorbis.stb_vorbis_decode_memory(
                        encoded,
                        channelsBuffer,
                        sampleRateBuffer
                );

                if (pcm == null) {
                    throw new IllegalStateException("Could not decode music.ogg");
                }

                int channels = channelsBuffer.get(0);
                int sampleRate = sampleRateBuffer.get(0);

                int format;

                if (channels == 1) {
                    format = AL10.AL_FORMAT_MONO16;
                } else if (channels == 2) {
                    format = AL10.AL_FORMAT_STEREO16;
                } else {
                    throw new IllegalStateException(
                            "Unsupported OGG channel count: " + channels
                    );
                }

                // Open independent OpenAL device and context.
                device = ALC10.alcOpenDevice((ByteBuffer) null);

                if (device == 0) {
                    throw new IllegalStateException("Could not open OpenAL device");
                }

                ALCCapabilities deviceCaps = ALC.createCapabilities(device);

                context = ALC10.alcCreateContext(device, (IntBuffer) null);

                if (context == 0) {
                    throw new IllegalStateException("Could not create OpenAL context");
                }

                if (!deviceCaps.ALC_EXT_thread_local_context) {
                    throw new IllegalStateException(
                            "OpenAL device does not support thread-local contexts"
                    );
                }

                if (!EXTThreadLocalContext.alcSetThreadContext(context)) {
                    throw new IllegalStateException("Could not activate thread-local OpenAL context");
                }
                threadLocalContext = true;

                AL.createCapabilities(deviceCaps);

                // Upload decoded audio.
                buffer = AL10.alGenBuffers();
                AL10.alBufferData(buffer, format, pcm, sampleRate);

                // Create looping audio source.
                source = AL10.alGenSources();

                AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
                AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);

                AL10.alSourcePlay(source);
            }

            // Keep the context alive until playback is stopped.
            while (!STOP_REQUESTED.get()) {
                Thread.sleep(100);
            }

        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();

        } catch (Throwable e) {
            System.err.println("[LoadingMusic] Audio playback failed:");
            e.printStackTrace();

        } finally {
            if (source != 0) {
                AL10.alSourceStop(source);
                AL10.alDeleteSources(source);
            }

            if (buffer != 0) {
                AL10.alDeleteBuffers(buffer);
            }

            if (context != 0) {
                if (threadLocalContext) {
                    EXTThreadLocalContext.alcSetThreadContext(0);
                    AL.setCurrentThread(null);
                }
                ALC10.alcDestroyContext(context);
            }

            if (device != 0) {
                ALC10.alcCloseDevice(device);
            }

            if (pcm != null) {
                MemoryUtil.memFree(pcm);
            }

            if (encoded != null) {
                MemoryUtil.memFree(encoded);
            }

            worker = null;
        }
    }
}