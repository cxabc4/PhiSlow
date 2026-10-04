package com.phislow.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;

/** Main-thread owner of playback state. Views only read its position. */
public final class AudioEngine {
    public interface Listener { void onChanged(String message); }
    /** Practice speed presets offered by both screens, and exercised by the device tests. */
    public static final float[] SPEEDS = {
        0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.25f, 1.5f, 2f
    };
    /** Shortest practice range the engine accepts, so a range always has audible content. */
    public static final int MIN_RANGE_MS = 500;
    private static final long RANGE_GUARD_MS = 25;
    private final Context context;
    private final Listener listener;
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private final Handler timer = new Handler(Looper.getMainLooper());
    /**
     * Practice-range boundary: playback stops the moment it reaches the end of the range.
     * Polling lives here (not in the Activity) so the rule holds no matter who drives playback.
     */
    private final Runnable rangeGuard = new Runnable() {
        @Override public void run() {
            if (!isPlaying()) return;
            if (hasPracticeRange() && positionMs() >= rangeEnd) { pause(); return; }
            timer.postDelayed(this, RANGE_GUARD_MS);
        }
    };
    private MediaPlayer player;
    private boolean ready, loading, seeking, resumeAfterSeek, rangeSeekInFlight;
    private int duration;
    private int rangeStart = -1, rangeEnd = -1;
    private float speed = 1f;

    public AudioEngine(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change < 0) pause();
            }).build();
    }

    /** null selects the original, locally bundled metronome sample. */
    public void load(Uri uri) {
        loadSource(uri, uri == null ? "practice-demo.wav" : null);
    }

    public void loadAsset(String asset) { loadSource(null, asset); }

    private void loadSource(Uri uri, String asset) {
        release();
        loading = true;
        MediaPlayer candidate = new MediaPlayer();
        player = candidate;
        candidate.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        candidate.setOnPreparedListener(mp -> {
            if (mp != player) return;
            loading = false;
            ready = true;
            duration = mp.getDuration();
            listener.onChanged(null);
        });
        candidate.setOnCompletionListener(mp -> {
            if (mp == player) {
                timer.removeCallbacks(rangeGuard);
                audioManager.abandonAudioFocusRequest(focusRequest);
                listener.onChanged(null);
            }
        });
        candidate.setOnSeekCompleteListener(mp -> {
            if (mp != player) return;
            seeking = false;
            if (resumeAfterSeek) play();
            listener.onChanged(null);
        });
        candidate.setOnErrorListener((mp, what, extra) -> {
            if (mp == player) {
                release();
                listener.onChanged("音频无法播放，请重新导入支持的音频文件");
            }
            return true;
        });
        try {
            if (asset != null && asset.startsWith(ImportedCharts.PREFIX)) {
                candidate.setDataSource(ImportedCharts.file(context, asset).getAbsolutePath());
            } else if (asset != null && asset.startsWith("library/")) {
                candidate.setDataSource(LibraryAssets.file(context, asset).getAbsolutePath());
            } else if (asset != null) {
                try (AssetFileDescriptor sample = context.getAssets().openFd(asset)) {
                    candidate.setDataSource(sample.getFileDescriptor(), sample.getStartOffset(), sample.getLength());
                }
            } else {
                candidate.setDataSource(context, uri);
            }
            candidate.prepareAsync();
            listener.onChanged(null);
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            release();
            listener.onChanged("无法读取音频，请选择本地可访问的音频文件");
        }
    }

    public boolean isReady() { return ready; }
    public boolean isLoading() { return loading; }
    public boolean isSeeking() { return seeking; }
    public boolean isPlaying() { return ready && player.isPlaying(); }
    public int durationMs() { return duration; }
    public int positionMs() { return ready ? player.getCurrentPosition() : 0; }
    public float speed() { return speed; }

    /** Labels a preset the way both screens show it: 0.5× / 1× / 1.25×. */
    public static String speedLabel(float value) {
        String text = value == Math.floor(value) ? Integer.toString((int) value) : Float.toString(value);
        return text + "×";
    }

    /**
     * Optional practice range: playback stops at {@code endMs} instead of the track end.
     * Ranges shorter than {@link #MIN_RANGE_MS} are rejected so a range always has content.
     */
    public boolean setPracticeRange(int startMs, int endMs) {
        if (!ready || duration <= 0) {
            listener.onChanged("载入音频后才能设置练习区间");
            return false;
        }
        int start = Math.max(0, Math.min(startMs, duration));
        int end = Math.max(0, Math.min(endMs, duration));
        if (end - start < MIN_RANGE_MS) {
            listener.onChanged("练习区间至少需要 " + (MIN_RANGE_MS / 1000f) + " 秒");
            return false;
        }
        rangeStart = start;
        rangeEnd = end;
        rangeSeekInFlight = false;
        listener.onChanged(null);
        return true;
    }

    public void clearPracticeRange() {
        rangeStart = -1;
        rangeEnd = -1;
        rangeSeekInFlight = false;
        listener.onChanged(null);
    }

    public boolean hasPracticeRange() { return rangeStart >= 0 && rangeEnd > rangeStart; }
    public int rangeStart() { return rangeStart < 0 ? 0 : rangeStart; }
    public int rangeEnd() { return rangeEnd < 0 ? duration : rangeEnd; }

    /** True when the playhead sits outside the practice range, so play must return to its start. */
    public boolean needsRangeJump() {
        if (!hasPracticeRange()) return false;
        int position = positionMs();
        return position >= rangeEnd || position < rangeStart;
    }

    public void play() {
        if (!ready || seeking) return;
        if (rangeSeekInFlight) {
            // The range seek just finished; resume from where it landed instead of looping.
            rangeSeekInFlight = false;
        } else if (hasPracticeRange() && needsRangeJump()) {
            rangeSeekInFlight = true;
            seek(rangeStart, true);
            return;
        } else if (!hasPracticeRange() && duration > 0 && positionMs() >= duration) {
            seek(0, true);
            return;
        }
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            listener.onChanged("暂时无法获得音频播放权限，请稍后重试");
            return;
        }
        if (!applySpeed(speed)) audioManager.abandonAudioFocusRequest(focusRequest);
        timer.removeCallbacks(rangeGuard);
        timer.postDelayed(rangeGuard, RANGE_GUARD_MS);
        listener.onChanged(null);
    }

    public void pause() {
        resumeAfterSeek = false;
        timer.removeCallbacks(rangeGuard);
        if (isPlaying()) player.pause();
        audioManager.abandonAudioFocusRequest(focusRequest);
        listener.onChanged(null);
    }

    public boolean setSpeed(float value) {
        if (isPlaying() && !applySpeed(value)) return false;
        // Non-zero setPlaybackParams starts a prepared/paused player; defer until play.
        speed = value;
        listener.onChanged(null);
        return true;
    }

    private boolean applySpeed(float value) {
        try {
            player.setPlaybackParams(new PlaybackParams().allowDefaults()
                .setSpeed(value).setPitch(1f)
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL));
            return true;
        } catch (IllegalArgumentException error) {
            listener.onChanged("此设备不支持该倍速，请选择其他档位");
            return false;
        }
    }

    public void seek(int positionMs, boolean playAfter) {
        if (!ready || seeking) return;
        seeking = true;
        pause();
        resumeAfterSeek = playAfter;
        player.seekTo(Math.max(0, Math.min(positionMs, duration)), MediaPlayer.SEEK_CLOSEST);
        listener.onChanged(null);
    }

    public void release() {
        ready = loading = seeking = resumeAfterSeek = rangeSeekInFlight = false;
        duration = 0;
        rangeStart = -1;
        rangeEnd = -1;
        timer.removeCallbacks(rangeGuard);
        audioManager.abandonAudioFocusRequest(focusRequest);
        MediaPlayer old = player;
        player = null;
        if (old != null) old.release();
    }
}
