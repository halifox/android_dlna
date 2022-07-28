package com.android.cast.dlna.dmr;

import android.net.Uri;
import android.widget.VideoView;

/**
 *
 */
public interface IDLNARenderControl {
    void play();

    void pause();

    void seek(long position);

    void stop();

    long getPosition();

    long getDuration();

    void cast(String currentURI, String currentURIMetaData);

    // -------------------------------------------------------------------------------------------
    // - VideoView impl
    // -------------------------------------------------------------------------------------------
    final class VideoViewRenderControl implements IDLNARenderControl {

        private final VideoView videoView;

        public VideoViewRenderControl(VideoView videoView) {
            this.videoView = videoView;
        }

        @Override
        public void play() {
            videoView.start();
        }

        @Override
        public void pause() {
            videoView.pause();
        }

        @Override
        public void seek(long position) {
            videoView.seekTo((int) position);
        }

        @Override
        public void stop() {
            videoView.stopPlayback();
        }

        @Override
        public long getPosition() {
            return videoView.getCurrentPosition();
        }

        @Override
        public long getDuration() {
            return videoView.getDuration();
        }

        @Override
        public void cast(String currentURI, String currentURIMetaData) {
            videoView.setVideoURI(Uri.parse(currentURI));
        }
    }

    // -------------------------------------------------------------------------------------------
    // - Default impl
    // -------------------------------------------------------------------------------------------
    final class DefaultRenderControl implements IDLNARenderControl {
        public DefaultRenderControl() {
        }

        @Override
        public void play() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void seek(long position) {
        }

        @Override
        public void stop() {
        }

        @Override
        public long getPosition() {
            return 0L;
        }

        @Override
        public long getDuration() {
            return 0L;
        }

        @Override
        public void cast(String currentURI, String currentURIMetaData) {

        }
    }
}
