package com.codegv.android.vcam.editor;

import android.content.Context;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Trims and crops a video into an H.264 MP4 suitable for virtual.mp4.
 * Video is decoded to a SurfaceTexture, drawn through GLES with the crop and
 * rotation applied, then re-encoded. AAC audio is copied without re-encoding.
 * {@link #transcode} blocks; call it from a background thread.
 */
public class VideoTranscoder {

    private static final String TAG = "VCAM-Transcoder";
    private static final String OUTPUT_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final long TIMEOUT_US = 10000;

    public interface ProgressListener {
        void onProgress(float fraction);
    }

    public static class Options {
        public long startUs;
        public long endUs;
        /** Normalized crop of the displayed (rotation-applied) frame. */
        public RectF crop = new RectF(0, 0, 1, 1);
        public int outWidth;
        public int outHeight;
        public boolean keepAudio = true;
        /** Turn the output upside down, for target apps that show the feed rotated 180°. */
        public boolean rotate180;
    }

    public static class CancelledException extends IOException {
        CancelledException() {
            super("cancelled");
        }
    }

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    /** @return true if audio was included in the output */
    public boolean transcode(Context context, Uri source, File output, Options opt,
                             ProgressListener listener) throws IOException {
        int rotation = readRotation(context, source);

        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        CodecInputSurface inputSurface = null;
        DecoderOutputSurface outputSurface = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            videoExtractor.setDataSource(context, source, null);
            int videoTrack = findTrack(videoExtractor, "video/");
            if (videoTrack < 0) {
                throw new IOException("no video track");
            }
            MediaFormat inputFormat = videoExtractor.getTrackFormat(videoTrack);
            // We apply rotation ourselves; stop the decoder from rotating its output too.
            if (inputFormat.containsKey("rotation-degrees")) {
                inputFormat.setInteger("rotation-degrees", 0);
            }

            MediaFormat audioFormat = null;
            int audioTrack = -1;
            if (opt.keepAudio) {
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(context, source, null);
                audioTrack = findTrack(audioExtractor, "audio/");
                if (audioTrack >= 0) {
                    MediaFormat f = audioExtractor.getTrackFormat(audioTrack);
                    if (AAC_MIME.equals(f.getString(MediaFormat.KEY_MIME))) {
                        audioFormat = f;
                    } else {
                        Log.i(TAG, "skipping non-AAC audio " + f.getString(MediaFormat.KEY_MIME));
                    }
                }
            }

            int frameRate = inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            if (frameRate <= 0 || frameRate > 60) {
                frameRate = 30;
            }
            MediaFormat outputFormat = MediaFormat.createVideoFormat(OUTPUT_MIME, opt.outWidth, opt.outHeight);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(opt.outWidth, opt.outHeight, frameRate));
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(OUTPUT_MIME);
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = new CodecInputSurface(encoder.createInputSurface());
            inputSurface.makeCurrent();
            encoder.start();

            outputSurface = new DecoderOutputSurface(opt.crop, rotation, opt.rotate180);
            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);
            decoder.start();

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            videoExtractor.selectTrack(videoTrack);
            videoExtractor.seekTo(opt.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            long rangeUs = Math.max(1, opt.endUs - opt.startUs);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, decoderDone = false, encoderDone = false;
            int muxVideoTrack = -1, muxAudioTrack = -1;
            int framesWritten = 0;

            while (!encoderDone) {
                if (cancelled) {
                    throw new CancelledException();
                }

                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = videoExtractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, videoExtractor.getSampleTime(), 0);
                            videoExtractor.advance();
                        }
                    }
                }

                // Drain everything the encoder has ready.
                while (true) {
                    int index = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw new IOException("encoder format changed twice");
                        }
                        muxVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                        if (audioFormat != null) {
                            muxAudioTrack = muxer.addTrack(audioFormat);
                        }
                        muxer.start();
                        muxerStarted = true;
                    } else if (index >= 0) {
                        ByteBuffer data = encoder.getOutputBuffer(index);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0; // already part of the format passed to addTrack
                        }
                        if (info.size > 0 && muxerStarted) {
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            muxer.writeSampleData(muxVideoTrack, data, info);
                        }
                        encoder.releaseOutputBuffer(index, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            break;
                        }
                    }
                }

                if (!decoderDone) {
                    int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (index >= 0) {
                        long pts = info.presentationTimeUs;
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean render = info.size > 0 && pts >= opt.startUs && pts <= opt.endUs;
                        decoder.releaseOutputBuffer(index, render);
                        if (render) {
                            outputSurface.awaitNewImage();
                            outputSurface.drawImage(opt.outWidth, opt.outHeight);
                            inputSurface.setPresentationTime((pts - opt.startUs) * 1000);
                            inputSurface.swapBuffers();
                            framesWritten++;
                            if (listener != null) {
                                float videoShare = audioFormat != null ? 0.95f : 1f;
                                listener.onProgress(videoShare * (pts - opt.startUs) / rangeUs);
                            }
                        }
                        // Decoder output is in presentation order, so past the end means we're done.
                        if (eos || pts > opt.endUs) {
                            if (framesWritten == 0) {
                                throw new IOException("no frames in the selected range");
                            }
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                            inputDone = true;
                        }
                    }
                }
            }

            boolean wroteAudio = false;
            if (audioFormat != null && muxAudioTrack >= 0) {
                copyAudio(audioExtractor, audioTrack, audioFormat, muxer, muxAudioTrack, opt);
                wroteAudio = true;
            }

            muxer.stop();
            muxerStarted = false;
            if (listener != null) {
                listener.onProgress(1f);
            }
            return wroteAudio;
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) { }
                decoder.release();
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) { }
                encoder.release();
            }
            if (outputSurface != null) {
                outputSurface.release();
            }
            if (inputSurface != null) {
                inputSurface.release();
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            videoExtractor.release();
            if (audioExtractor != null) {
                audioExtractor.release();
            }
        }
    }

    private void copyAudio(MediaExtractor extractor, int track, MediaFormat format,
                           MediaMuxer muxer, int muxTrack, Options opt) throws IOException {
        extractor.selectTrack(track);
        extractor.seekTo(opt.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        int capacity = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 256 * 1024;
        ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (cancelled) {
                throw new CancelledException();
            }
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            long t = extractor.getSampleTime();
            if (t > opt.endUs) {
                break;
            }
            if (t >= opt.startUs) {
                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, t - opt.startUs, flags);
                muxer.writeSampleData(muxTrack, buffer, info);
            }
            extractor.advance();
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static int bitrateFor(int w, int h, int fps) {
        long bps = (long) (w * (long) h * fps * 0.12);
        return (int) Math.max(1_000_000, Math.min(12_000_000, bps));
    }

    static int readRotation(Context context, Uri source) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, source);
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int deg = rot == null ? 0 : Integer.parseInt(rot);
            return ((deg % 360) + 360) % 360;
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * Rounds {@code w x h} to a size the device's AVC encoder accepts, shrinking
     * slightly if needed. Returns {width, height}.
     */
    public static int[] fitEncoderSize(int w, int h) {
        MediaCodecInfo.VideoCapabilities caps = findAvcEncoderCaps();
        if (caps == null) {
            return new int[]{align(w, 16), align(h, 16)};
        }
        int wa = Math.max(2, caps.getWidthAlignment());
        int ha = Math.max(2, caps.getHeightAlignment());
        int aw = align(w, wa), ah = align(h, ha);
        for (int i = 0; i < 64 && !caps.isSizeSupported(aw, ah); i++) {
            // Scale down by ~5% per step, keeping the aspect ratio.
            aw = align(Math.round(aw * 0.95f), wa);
            ah = align(Math.round(ah * 0.95f), ha);
        }
        return new int[]{aw, ah};
    }

    private static int align(int v, int a) {
        return Math.max(a, Math.round(v / (float) a) * a);
    }

    private static MediaCodecInfo.VideoCapabilities avcEncoderCaps;

    private static synchronized MediaCodecInfo.VideoCapabilities findAvcEncoderCaps() {
        if (avcEncoderCaps != null) {
            return avcEncoderCaps;
        }
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(OUTPUT_MIME)) {
                    avcEncoderCaps = info.getCapabilitiesForType(type).getVideoCapabilities();
                    return avcEncoderCaps;
                }
            }
        }
        return null;
    }
}
