package com.halifox.dlna.demo;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.android.cast.dlna.dmr.DLNARendererService;
import com.android.cast.dlna.dmr.IDLNARenderControl;

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.model.TransportState;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Minimal Java/XML DLNA MediaRenderer example.
 *
 * <p>The library receives UPnP AVTransport commands. This activity supplies
 * the media control implementation, so the existing renderer service can
 * play the URL without launching the library's renderer activity.</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final UnsignedIntegerFourBytes INSTANCE_ID = new UnsignedIntegerFourBytes(0);
    private static final int PROGRESS_MAX = 1000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            mainHandler.postDelayed(this, 500L);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            DLNARendererService.RendererServiceBinder binder =
                    (DLNARendererService.RendererServiceBinder) service;
            rendererService = binder.getRendererService();
            rendererService.setRenderControl(new ReceiverRenderControl());
            serviceStatus.setText(R.string.status_waiting);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            rendererService = null;
            serviceBound = false;
            serviceStatus.setText(R.string.status_error);
        }
    };

    private ImageView albumArt;
    private TextView serviceStatus;
    private TextView trackTitle;
    private TextView trackArtist;
    private TextView trackAlbum;
    private TextView currentPosition;
    private TextView totalDuration;
    private TextView sourceUri;
    private SeekBar playbackProgress;
    private Button playPauseButton;

    private DLNARendererService rendererService;
    private boolean serviceBound;
    private boolean userSeeking;
    private boolean pendingPlay;
    private boolean mediaPrepared;
    private boolean activityDestroyed;
    private MediaPlayer mediaPlayer;
    private volatile long positionMs;
    private volatile long durationMs;
    private String currentCoverUri;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupLocalControls();

        serviceStatus.setText(R.string.status_starting);
        DLNARendererService.startService(this);
        serviceBound = bindService(
                new Intent(this, DLNARendererService.class),
                serviceConnection,
                BIND_AUTO_CREATE
        );
        mainHandler.post(progressUpdater);
    }

    private void bindViews() {
        albumArt = findViewById(R.id.album_art);
        serviceStatus = findViewById(R.id.service_status);
        trackTitle = findViewById(R.id.track_title);
        trackArtist = findViewById(R.id.track_artist);
        trackAlbum = findViewById(R.id.track_album);
        currentPosition = findViewById(R.id.current_position);
        totalDuration = findViewById(R.id.total_duration);
        sourceUri = findViewById(R.id.source_uri);
        playbackProgress = findViewById(R.id.playback_progress);
        playPauseButton = findViewById(R.id.play_pause_button);
    }

    private void setupLocalControls() {
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mediaPlayer != null && mediaPrepared && mediaPlayer.isPlaying()) {
                    pausePlayback();
                } else {
                    playPlayback();
                }
            }
        });

        findViewById(R.id.stop_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
            }
        });

        playbackProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0L) {
                    positionMs = durationMs * progress / PROGRESS_MAX;
                    currentPosition.setText(formatTime(positionMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                seekPlayback(durationMs * seekBar.getProgress() / PROGRESS_MAX);
            }
        });
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        mainHandler.removeCallbacks(progressUpdater);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        DLNARendererService.stopService(this);
        releasePlayer();
        super.onDestroy();
    }

    private final class ReceiverRenderControl implements IDLNARenderControl {
        @Override
        public void play() {
            postToMain(new Runnable() {
                @Override
                public void run() {
                    playPlayback();
                }
            });
        }

        @Override
        public void pause() {
            postToMain(new Runnable() {
                @Override
                public void run() {
                    pausePlayback();
                }
            });
        }

        @Override
        public void seek(final long position) {
            postToMain(new Runnable() {
                @Override
                public void run() {
                    seekPlayback(position);
                }
            });
        }

        @Override
        public void stop() {
            postToMain(new Runnable() {
                @Override
                public void run() {
                    stopPlayback();
                }
            });
        }

        @Override
        public long getPosition() {
            return positionMs;
        }

        @Override
        public long getDuration() {
            return durationMs;
        }

        @Override
        public void cast(final String uri, final String metadata) {
            postToMain(new Runnable() {
                @Override
                public void run() {
                    openTrack(uri, metadata);
                }
            });
        }
    }

    private void openTrack(String uri, String metadata) {
        TrackMetadata track = parseMetadata(uri, metadata);
        if (TextUtils.isEmpty(track.uri)) {
            serviceStatus.setText(R.string.status_error);
            return;
        }

        currentCoverUri = track.albumArtUri;
        trackTitle.setText(track.title);
        trackArtist.setText(track.artist);
        trackAlbum.setText(track.album);
        sourceUri.setText(getString(R.string.source_uri_format, track.uri));
        albumArt.setImageResource(R.mipmap.ic_launcher);
        loadAlbumArt(track.albumArtUri);

        pendingPlay = true;
        releasePlayer();
        serviceStatus.setText(R.string.status_loading);
        playPauseButton.setText(R.string.action_pause);

        final MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        mediaPrepared = false;
        positionMs = 0L;
        durationMs = 0L;
        updateProgress();

        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (mediaPlayer != player || activityDestroyed) {
                    return;
                }
                mediaPrepared = true;
                durationMs = Math.max(0L, mp.getDuration());
                if (pendingPlay) {
                    pendingPlay = false;
                    mp.start();
                    serviceStatus.setText(R.string.status_playing);
                    playPauseButton.setText(R.string.action_pause);
                    notifyTransportStateChanged(TransportState.PLAYING);
                }
                updateProgress();
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (mediaPlayer != player || activityDestroyed) {
                    return;
                }
                positionMs = durationMs;
                serviceStatus.setText(R.string.status_stopped);
                playPauseButton.setText(R.string.action_play);
                notifyTransportStateChanged(TransportState.STOPPED);
                updateProgress();
            }
        });
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (mediaPlayer == player) {
                    serviceStatus.setText(R.string.status_error);
                    playPauseButton.setText(R.string.action_play);
                    mediaPrepared = false;
                    pendingPlay = false;
                    notifyTransportStateChanged(TransportState.STOPPED);
                    releasePlayer();
                }
                return true;
            }
        });

        try {
            player.setDataSource(this, Uri.parse(track.uri));
            player.prepareAsync();
        } catch (IOException | RuntimeException exception) {
            serviceStatus.setText(R.string.status_error);
            mediaPrepared = false;
            pendingPlay = false;
            notifyTransportStateChanged(TransportState.STOPPED);
            releasePlayer();
        }
    }

    private void playPlayback() {
        if (mediaPlayer == null) {
            pendingPlay = true;
            return;
        }
        if (!mediaPrepared) {
            pendingPlay = true;
            return;
        }
        try {
            if (durationMs > 0L && positionMs >= durationMs) {
                mediaPlayer.seekTo(0);
                positionMs = 0L;
            }
            mediaPlayer.start();
            pendingPlay = false;
            serviceStatus.setText(R.string.status_playing);
            playPauseButton.setText(R.string.action_pause);
            notifyTransportStateChanged(TransportState.PLAYING);
        } catch (IllegalStateException exception) {
            serviceStatus.setText(R.string.status_error);
        }
    }

    private void pausePlayback() {
        pendingPlay = false;
        if (mediaPlayer == null || !mediaPrepared) {
            return;
        }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
            positionMs = mediaPlayer.getCurrentPosition();
            serviceStatus.setText(R.string.status_paused);
            playPauseButton.setText(R.string.action_play);
            notifyTransportStateChanged(TransportState.PAUSED_PLAYBACK);
        } catch (IllegalStateException exception) {
            serviceStatus.setText(R.string.status_error);
        }
    }

    private void seekPlayback(long position) {
        if (mediaPlayer == null || !mediaPrepared) {
            return;
        }
        long safePosition = Math.max(0L, Math.min(position, durationMs));
        try {
            mediaPlayer.seekTo((int) safePosition);
            positionMs = safePosition;
            updateProgress();
        } catch (IllegalStateException exception) {
            serviceStatus.setText(R.string.status_error);
        }
    }

    private void stopPlayback() {
        pendingPlay = false;
        releasePlayer();
        positionMs = 0L;
        serviceStatus.setText(R.string.status_stopped);
        playPauseButton.setText(R.string.action_play);
        updateProgress();
        notifyTransportStateChanged(TransportState.STOPPED);
    }

    private void releasePlayer() {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        mediaPrepared = false;
        if (player != null) {
            try {
                player.reset();
            } catch (IllegalStateException ignored) {
                // The player may already be in an error state.
            }
            player.release();
        }
    }

    private void updateProgress() {
        if (mediaPlayer != null && mediaPrepared && !userSeeking) {
            try {
                positionMs = Math.max(0L, mediaPlayer.getCurrentPosition());
            } catch (IllegalStateException ignored) {
                // The asynchronous MediaPlayer callback can race with release.
            }
        }
        currentPosition.setText(formatTime(positionMs));
        totalDuration.setText(formatTime(durationMs));
        playbackProgress.setEnabled(durationMs > 0L);
        if (!userSeeking) {
            int progress = durationMs <= 0L
                    ? 0
                    : (int) Math.min(PROGRESS_MAX, positionMs * PROGRESS_MAX / durationMs);
            playbackProgress.setProgress(progress);
        }
    }

    private void loadAlbumArt(final String albumArtUri) {
        if (TextUtils.isEmpty(albumArtUri)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(albumArtUri);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    connection.setInstanceFollowRedirects(true);
                    connection.connect();
                    if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                        try (InputStream inputStream = connection.getInputStream()) {
                            bitmap = BitmapFactory.decodeStream(inputStream);
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // The default app icon remains visible when artwork is unavailable.
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }

                final Bitmap loadedBitmap = bitmap;
                if (loadedBitmap != null) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!activityDestroyed && TextUtils.equals(currentCoverUri, albumArtUri)) {
                                albumArt.setImageBitmap(loadedBitmap);
                            }
                        }
                    });
                }
            }
        }, "dlna-album-art").start();
    }

    private void notifyTransportStateChanged(TransportState transportState) {
        if (rendererService == null || rendererService.getAvTransportLastChange() == null) {
            return;
        }
        rendererService.getAvTransportLastChange().setEventedValue(
                INSTANCE_ID,
                new AVTransportVariable.TransportState(transportState)
        );
        if (rendererService.avTransportServiceLastChangeAwareServiceManager != null) {
            rendererService.avTransportServiceLastChangeAwareServiceManager.fireLastChange();
        }
    }

    private void postToMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private static TrackMetadata parseMetadata(String currentUri, String metadata) {
        TrackMetadata result = new TrackMetadata();
        result.uri = currentUri;
        result.title = "未知歌曲";
        result.artist = "未知歌手";
        result.album = "未知专辑";

        if (!TextUtils.isEmpty(metadata)) {
            try {
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                factory.setNamespaceAware(true);
                XmlPullParser parser = factory.newPullParser();
                parser.setInput(new StringReader(metadata));
                int eventType = parser.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        String tag = parser.getName().toLowerCase(Locale.US);
                        if ("res".equals(tag)) {
                            String resourceUri = parser.nextText();
                            if (TextUtils.isEmpty(result.uri) && !TextUtils.isEmpty(resourceUri)) {
                                result.uri = resourceUri.trim();
                            }
                        } else if ("title".equals(tag)) {
                            result.title = readText(parser, result.title);
                        } else if ("creator".equals(tag) || "artist".equals(tag)) {
                            result.artist = readText(parser, result.artist);
                        } else if ("album".equals(tag)) {
                            result.album = readText(parser, result.album);
                        } else if ("albumarturi".equals(tag)) {
                            result.albumArtUri = readText(parser, null);
                        }
                    }
                    eventType = parser.next();
                }
            } catch (Exception ignored) {
                // Some senders omit or truncate DIDL-Lite. URI playback still works.
            }
        }

        result.title = fallback(result.title, "未知歌曲");
        result.artist = fallback(result.artist, "未知歌手");
        result.album = fallback(result.album, "未知专辑");
        if (result.uri != null) {
            result.uri = result.uri.trim();
        }
        if (result.albumArtUri != null) {
            result.albumArtUri = result.albumArtUri.trim();
        }
        return result;
    }

    @Nullable
    private static String readText(XmlPullParser parser, @Nullable String fallback) throws Exception {
        String text = parser.nextText();
        return TextUtils.isEmpty(text) ? fallback : text.trim();
    }

    private static String fallback(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static final class TrackMetadata {
        private String uri;
        private String title;
        private String artist;
        private String album;
        private String albumArtUri;
    }
}
