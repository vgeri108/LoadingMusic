package com.vgeri108.loadingmusic;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EarlyMusicPlayer {

    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean(false);
    private static volatile Thread playerThread;

    private EarlyMusicPlayer() {
    }

    public static synchronized void start() {
        if (playerThread != null && playerThread.isAlive()) {
            return;
        }

        STOP_REQUESTED.set(false);

        playerThread = new Thread(EarlyMusicPlayer::play, "LoadingMusic-Audio");
        playerThread.setDaemon(true);
        playerThread.start();
    }

    public static void stop() {
        STOP_REQUESTED.set(true);

        Thread thread = playerThread;

        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void play() {
        long device = 0;
        long context = 0;
        int buffer = 0;
        int source = 0;

        ShortBuffer decoded = null;

        try {
            byte[] fileData;

            try (InputStream stream = EarlyMusicPlayer.class.getResourceAsStream(
                    "/assets/loadingmusic/sounds/music.ogg")) {

                if (stream == null) {
                    throw new IllegalStateException("music.ogg not found");
                }

                fileData = stream.readAllBytes();
            }

            ByteBuffer encoded = MemoryUtil.memAlloc(fileData.length);

            try {
                encoded.put(fileData).flip();

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer channels = stack.mallocInt(1);
                    IntBuffer sampleRate = stack.mallocInt(1);

                    decoded = STBVorbis.stb_vorbis_decode_memory(
                            encoded,
                            channels,
                            sampleRate
                    );

                    if (decoded == null) {
                        throw new IllegalStateException("OGG decoding failed");
                    }

                    int channelCount = channels.get(0);

                    if (channelCount != 1 && channelCount != 2) {
                        throw new IllegalStateException(
                                "Unsupported audio channel count: " + channelCount
                        );
                    }

                    device = ALC10.alcOpenDevice((ByteBuffer) null);

                    if (device == 0) {
                        throw new IllegalStateException("Could not open OpenAL device");
                    }

                    ALCCapabilities alcCapabilities = ALC.createCapabilities(device);

                    context = ALC10.alcCreateContext(device, (IntBuffer) null);

                    if (context == 0) {
                        throw new IllegalStateException("Could not create OpenAL context");
                    }

                    ALC10.alcMakeContextCurrent(context);
                    AL.createCapabilities(alcCapabilities);

                    buffer = AL10.alGenBuffers();
                    source = AL10.alGenSources();

                    int format = channelCount == 1
                            ? AL10.AL_FORMAT_MONO16
                            : AL10.AL_FORMAT_STEREO16;

                    AL10.alBufferData(
                            buffer,
                            format,
                            decoded,
                            sampleRate.get(0)
                    );

                    AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
                    AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);

                    if (!STOP_REQUESTED.get()) {
                        AL10.alSourcePlay(source);
                    }
                }
            } finally {
                MemoryUtil.memFree(encoded);
            }

            while (!STOP_REQUESTED.get()) {
                Thread.sleep(100);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            System.err.println("[LoadingMusic] Playback error:");
            e.printStackTrace();
        } finally {
            try {
                if (source != 0) {
                    AL10.alDeleteSources(source);
                }

                if (buffer != 0) {
                    AL10.alDeleteBuffers(buffer);
                }

                if (context != 0) {
                    ALC10.alcMakeContextCurrent(0);
                    ALC10.alcDestroyContext(context);
                }

                if (device != 0) {
                    ALC10.alcCloseDevice(device);
                }
            } catch (Throwable e) {
                System.err.println("[LoadingMusic] Audio cleanup error:");
                e.printStackTrace();
            }

            if (decoded != null) {
                MemoryUtil.memFree(decoded);
            }
        }
    }
}
