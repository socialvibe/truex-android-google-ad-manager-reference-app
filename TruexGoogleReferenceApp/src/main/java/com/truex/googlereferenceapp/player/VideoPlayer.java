/*
 * Copyright 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.truex.googlereferenceapp.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.Window;

import androidx.annotation.OptIn;
import androidx.appcompat.view.menu.ShowableListMenu;
import androidx.appcompat.widget.ForwardingListener;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.extractor.metadata.emsg.EventMessage;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.ui.PlayerView;

import com.google.ads.interactivemedia.v3.api.CuePoint;
import com.google.ads.interactivemedia.v3.api.StreamManager;

import java.util.Formatter;
import java.util.List;
import java.util.Locale;

/**
 * A video player that plays HLS or DASH streams using ExoPlayer.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoPlayer {

    private static final String CLASSTAG = VideoPlayer.class.getSimpleName();

    private final Context context;

    private ExoPlayer player;
    private final PlayerView playerView;
    private VideoPlayerCallback playerCallback;

    private String streamUrl;
    private Boolean streamRequested;
    private boolean canSeek;

    private StreamManager streamManager;
    private Timeline timelineWithAds;

    public VideoPlayer(Context context, PlayerView playerView) {
        this.context = context;
        this.playerView = playerView;
        streamRequested = false;
        canSeek = true;
        initPlayer();
    }

    private long streamToContentMs(long position) {
        if (position == C.TIME_UNSET || position == 0 || streamManager == null) return position;
        return streamManager.getContentTimeMsForStreamTimeMs(position);
    }

    private long contentToStreamMs(long position) {
        if (position == C.TIME_UNSET || position == 0 || streamManager == null) return position;
        return streamManager.getStreamTimeMsForContentTimeMs(position);
    }

    static public String positionDisplay(long position) {
        StringBuilder formatBuilder = new StringBuilder();
        Formatter formatter = new Formatter(formatBuilder, Locale.getDefault());
        String timeDisplay = Util.getStringForTime(formatBuilder, formatter, position);
        return timeDisplay;
    }

    static public void logPosition(String context, long position) {
        logPosition(context, position, C.TIME_UNSET);
    }

    static public void logPosition(String context, long position, long rawPosition) {
        StringBuilder msg = new StringBuilder();
        msg.append("*** ");
        msg.append(context);
        msg.append(": ");
        msg.append(positionDisplay(position));
        if (rawPosition != C.TIME_UNSET && position != rawPosition) {
            msg.append(" (raw: ");
            msg.append(positionDisplay(rawPosition));
            msg.append(")");
        }
        Log.i(CLASSTAG, msg.toString());
    }

    private void initPlayer() {
        release();

        player = new ExoPlayer.Builder(context).build();

        ForwardingPlayer playerWrapper = new ForwardingPlayer(player) {
            @Override
            public void seekToDefaultPosition() {
                seekToDefaultPosition(getCurrentMediaItemIndex());
            }

            @Override
            public void seekToDefaultPosition(int windowIndex) {
                seekTo(windowIndex, /* positionMs= */ C.TIME_UNSET);
            }

            @Override
            public void seekTo(long positionMs) {
                seekTo(getCurrentMediaItemIndex(), positionMs);
            }

            @Override
            public void seekTo(int windowIndex, long contentPosition) {
                if (!canSeek) return;
                long seekPos = contentToStreamMs(contentPosition);
                if (playerCallback != null) {
                    logPosition("onSeek", contentPosition, seekPos);
                    playerCallback.onSeek(windowIndex, seekPos);
                } else {
                    logPosition("seekTo", contentPosition, seekPos);
                    player.seekTo(windowIndex, seekPos);
                }
            }

            @Override
            public Timeline getCurrentTimeline() {
                if (timelineWithAds != null) return timelineWithAds;
                return player.getCurrentTimeline();
            }

            @Override
            public long getContentPosition() {
                // Display content position instead of raw stream position to player view.
                long streamPos = player.getContentPosition();
                long result = streamToContentMs(streamPos);
                logPosition("getContentPosition", result, streamPos);
                return result;
            }

            @Override
            public long getContentDuration() {
                // Display content duration instead of raw stream position to player view.
                long streamPos = player.getContentDuration();
                long result = streamToContentMs(streamPos);
                logPosition("getContentDuration", result, streamPos);
                return result;
            }

            @Override
            public long getContentBufferedPosition() {
                long streamPos = player.getContentBufferedPosition();
                long result = streamToContentMs(streamPos);
                //logPosition("getContentBufferedPosition", result, streamPos);
                return result;
            }

            @Override
            public long getCurrentPosition() {
                long streamPos = player.getCurrentPosition();
                long result = streamToContentMs(streamPos);
                logPosition("getCurrentPosition", result, streamPos);
                return result;
            }

            @Override
            public long getDuration() {
                long streamPos = player.getDuration();
                long result = streamToContentMs(streamPos);
                logPosition("getDuration", result, streamPos);
                return result;
            }

            @Override
            public long getBufferedPosition() {
                long streamPos = player.getBufferedPosition();
                long result = streamToContentMs(streamPos);
                //logPosition("getBufferedPosition", result, streamPos);
                return result;
            }

            @Override
            public long getTotalBufferedDuration() {
                long streamPos = player.getTotalBufferedDuration();
                long result = streamToContentMs(streamPos);
                //logPosition("getTotalBufferedDuration", result, streamPos);
                return result;
            }

            // In theory we should convert stream positions to content positions,
            // but in practice the player view only queries the player wrapper for the updated
            // position.
//            @Override
//            public void addListener(Listener listener) {
//                ForwardingListener listenerWrapper = new ForwardingListener(this, listener) {
//                  ...override and convert position discontinuity events
//                };
//                player.addListener(listenerWrapper);
//            }
        };
        playerView.setPlayer(playerWrapper);
    }

    public void play() {
        if (player == null) {
            initPlayer();
        }

        if (streamRequested) {
            // Stream requested, just resume.
            player.play();
            return;
        }

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context);
        int type = Util.inferContentType(Uri.parse(streamUrl));
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(streamUrl));
        MediaSource mediaSource;
        switch (type) {
            case C.CONTENT_TYPE_HLS:
                mediaSource = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
                break;
            case C.CONTENT_TYPE_DASH:
                mediaSource =
                        new DashMediaSource.Factory(
                                new DefaultDashChunkSource.Factory(dataSourceFactory), dataSourceFactory)
                                .createMediaSource(mediaItem);
                break;
            case C.CONTENT_TYPE_OTHER:
                mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
                break;
            default:
                throw new UnsupportedOperationException("Unknown stream type.");
        }

        player.setMediaSource(mediaSource);
        player.prepare();

        // Register for ID3 events.
        player.addListener(
                new Player.Listener() {
                    @Override
                    public void onMetadata(Metadata metadata) {
                        for (int i = 0; i < metadata.length(); i++) {
                            Metadata.Entry entry = metadata.get(i);
                            if (entry instanceof TextInformationFrame) {
                                TextInformationFrame textFrame = (TextInformationFrame) entry;
                                if ("TXXX".equals(textFrame.id)) {
                                    Log.d(CLASSTAG, "Received user text: " + textFrame.value);
                                    if (playerCallback != null) {
                                        playerCallback.onUserTextReceived(textFrame.value);
                                    }
                                }
                            } else if (entry instanceof EventMessage) {
                                EventMessage eventMessage = (EventMessage) entry;
                                String eventMessageValue = new String(eventMessage.messageData);
                                Log.d(CLASSTAG, "Received user text: " + eventMessageValue);
                                if (playerCallback != null) {
                                    playerCallback.onUserTextReceived(eventMessageValue);
                                }
                            }
                        }
                    }
                });

        player.play();
        streamRequested = true;
    }

    public void pause() {
        player.pause();
    }

    public void hide() {
        playerView.setVisibility(View.GONE);
    }

    public void show() {
        playerView.setVisibility(View.VISIBLE);
    }

    public void seekTo(long positionMs) {
        logPosition("raw seekTo", positionMs);
        player.seekTo(positionMs);
    }

    public void seekTo(int windowIndex, long positionMs) {
        logPosition("raw seekTo", positionMs);
        player.seekTo(windowIndex, positionMs);
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
            streamRequested = false;
        }
    }

    public void setStreamUrl(String streamUrl) {
        this.streamUrl = streamUrl;
        streamRequested = false; // request new stream on play
    }

    public void setAdsTimeline(StreamManager withStreamManager) {
        if (streamManager == withStreamManager) return;
        this.streamManager = withStreamManager;
        if (withStreamManager == null) {
            this.timelineWithAds = null;
        } else {
            // Use a timeline that displays content times as opposed to the raw stream times.
            // I.e. discount the ad time periods.
            Timeline streamTimeline = player.getCurrentTimeline();
            this.timelineWithAds = new ForwardingTimeline(streamTimeline) {
                @Override
                public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
                    Window result = super.getWindow(windowIndex, window, defaultPositionProjectionUs);
                    if (result.durationUs != C.TIME_UNSET) {
                        long streamDuration = player.getDuration();
                        long contentDuration = streamToContentMs(streamDuration);
                        logPosition("getWindow duration", contentDuration, streamDuration);
                        result.durationUs = Util.msToUs(contentDuration);
                    }
                    return result;
                }

                @Override
                public Period getPeriod(int periodIndex, Period period, boolean setIds) {
                    Period result = super.getPeriod(periodIndex, period, setIds);
                    if (result.durationUs != C.TIME_UNSET) {
                        long streamDuration = player.getDuration();
                        long contentDuration = streamToContentMs(streamDuration);
                        logPosition("getPeriod duration", contentDuration, streamDuration);
                        result.durationUs = Util.msToUs(contentDuration);
                    }
                    return result;
                }
            };
        }
        refreshAdMarkers();
    }

    public void refreshAdMarkers() {
        long[] extraAdGroupTimesMs = null;
        boolean[] extraPlayedAdGroups = null;
        if (streamManager != null) {
            // Set up the ad markers.
            List<CuePoint> adBreaks = streamManager.getCuePoints();
            extraAdGroupTimesMs = new long[adBreaks.size()];
            extraPlayedAdGroups = new boolean[adBreaks.size()];
            for (int i = 0; i < adBreaks.size(); i++) {
                CuePoint adBreak = adBreaks.get(i);
                extraAdGroupTimesMs[i] = streamManager.getContentTimeMsForStreamTimeMs(adBreak.getStartTimeMs());
                extraPlayedAdGroups[i] = adBreak.isPlayed();
            }
        }
        playerView.setExtraAdGroupMarkers(extraAdGroupTimesMs, extraPlayedAdGroups);
    }

    public boolean isPlayingAd() {
        return streamManager != null && streamManager.getAdProgressInfo() != null;
    }

    public void enableControls(boolean doEnable) {
        if (doEnable) {
            playerView.showController();
        } else {
            playerView.hideController();
        }
        playerView.setControllerAutoShow(doEnable);
        playerView.setUseController(doEnable);
        canSeek = doEnable;
    }

    public void requestFocus() {
        playerView.requestFocus();
    }

    public boolean isStreamRequested() {
        return streamRequested;
    }

    // Methods for exposing player information.
    void setCallback(VideoPlayerCallback callback) {
        playerCallback = callback;
    }

    public void setCanSeek(boolean canSeek) {
        this.canSeek = canSeek;
    }

    /**
     * Returns current position of the playhead in milliseconds for DASH and HLS stream.
     */
    public long getCurrentPositionMs() {
        if (player == null) return 0;

        Timeline currentTimeline = player.getCurrentTimeline();
        if (currentTimeline.isEmpty()) {
            return player.getCurrentPosition();
        }
        Timeline.Window window = new Timeline.Window();
        player.getCurrentTimeline().getWindow(player.getCurrentMediaItemIndex(), window);
        if (window.isLive()) {
            return player.getCurrentPosition() + window.windowStartTimeMs;
        } else {
            return player.getCurrentPosition();
        }
    }

    public void enableRepeatOnce() {
        if (player != null) player.setRepeatMode(Player.REPEAT_MODE_ONE);
    }

    public void setVolume(float volume) {
        if (player != null) player.setVolume(volume);
    }

    public int getVolume() {
        return player == null ? 0 : Math.round(player.getVolume() * 100);
    }

    public long getDuration() {
        return player == null ? 0 : player.getDuration();
    }
}
