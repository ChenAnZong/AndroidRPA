package noadb;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ScreenDecoder {

    private static final int VIDEO_WIDTH = 2160;
    private static final int VIDEO_HEIGHT = 3840;
    private static final long DECODE_TIME_OUT = 10000;
    private static final int SCREEN_FRAME_RATE = 20;
    private static final int SCREEN_FRAME_INTERVAL = 1;
    private MediaCodec mMediaCodec;

    public ScreenDecoder() {}

    public void startDecode(Surface surface) {
        try {
            // 配置MediaCodec
            mMediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, VIDEO_WIDTH, VIDEO_HEIGHT);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_WIDTH * VIDEO_HEIGHT);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, SCREEN_FRAME_RATE);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, SCREEN_FRAME_INTERVAL);
            mMediaCodec.configure(mediaFormat, surface, null, 0);
            mMediaCodec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void decodeData(byte[] data) {
        int index = mMediaCodec.dequeueInputBuffer(DECODE_TIME_OUT);
        if (index >= 0) {
            ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(index);
            inputBuffer.clear();
            inputBuffer.put(data, 0, data.length);
            mMediaCodec.queueInputBuffer(index, 0, data.length, System.currentTimeMillis(), 0);
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, DECODE_TIME_OUT);
        while (outputBufferIndex > 0) {
            mMediaCodec.releaseOutputBuffer(outputBufferIndex, true);
            outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 0);
        }
    }

    public void stopDecode() {
        if (mMediaCodec != null) {
            mMediaCodec.release();
        }
    }
}
