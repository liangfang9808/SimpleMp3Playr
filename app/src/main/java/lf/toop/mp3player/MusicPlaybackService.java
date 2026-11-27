
package lf.toop.mp3player;

import android.app.Service;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.widget.RemoteViews;

import java.io.File;
import java.util.ArrayList;

public class MusicPlaybackService extends Service {

    public static final String ACTION_PREVIOUS = "lf.toop.mp3player.PREVIOUS";
    public static final String ACTION_PLAY_PAUSE = "lf.toop.mp3player.PLAY_PAUSE";
    public static final String ACTION_NEXT = "lf.toop.mp3player.NEXT";

    private static final int NOTIFICATION_ID = 1001;

    private static final String PREFS_NAME = "player_history";
    private static final String KEY_LAST_PLAYED = "last_played_path";

    private final IBinder binder = new MusicBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;

    private ArrayList<String> playlist = new ArrayList<>();
    private int currentIndex = -1;

    private String currentPath = "";

    private boolean prepared = false;
    private boolean preparing = false;
    private boolean foregroundStarted = false;

    private PlaybackListener playbackListener;

    /**
     * Activity 通过该接口接收播放状态。
     */
    public interface PlaybackListener {
        void onPlaybackChanged(
                String path,
                int index,
                int duration,
                int position,
                boolean playing,
                boolean preparing,
                boolean completed
        );
    }

    public class MusicBinder extends Binder {

        public void setListener(PlaybackListener listener) {
            playbackListener = listener;
            notifyPlaybackChanged(false);
        }

        public void playPlaylist(ArrayList<String> files, int index) {
            MusicPlaybackService.this.playPlaylist(files, index);
        }

        public void togglePlayback() {
            MusicPlaybackService.this.togglePlayback();
        }

        public void playPrevious() {
            MusicPlaybackService.this.playPrevious();
        }

        public void playNext() {
            MusicPlaybackService.this.playNext();
        }

        public void seekTo(int position) {
            MusicPlaybackService.this.seekTo(position);
        }

        public boolean isPlaying() {
            return MusicPlaybackService.this.isPlaying();
        }

        public boolean isPreparing() {
            return preparing;
        }

        public int getDuration() {
            return MusicPlaybackService.this.getDuration();
        }

        public int getPosition() {
            return MusicPlaybackService.this.getPosition();
        }

        public String getCurrentPath() {
            return currentPath;
        }

        public int getCurrentIndex() {
            return currentIndex;
        }
        public void setCurrentIndex(int index) {
              currentIndex=index;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 创建系统媒体会话
        mediaSession = new MediaSessionCompat(this, "SimpleMp3Player");
// 启用系统媒体按键和传输控制
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setCallback(new MediaSessionCompat.Callback() {

            @Override
            public void onPlay() {
                resumePlayback();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onSkipToPrevious() {
                playPrevious();
            }

            @Override
            public void onSkipToNext() {
                playNext();
            }

            @Override
            public void onSeekTo(long position) {
                seekTo((int) position);
            }

        });

        mediaSession.setActive(true);

        // 定期同步系统媒体会话进度和通知栏进度
        handler.post(progressRunnable);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && intent.getAction() != null) {

            String action = intent.getAction();

            switch (action) {

                case ACTION_PREVIOUS:
                    playPrevious();
                    break;

                case ACTION_PLAY_PAUSE:
                    togglePlayback();
                    break;

                case ACTION_NEXT:
                    playNext();
                    break;
            }
        }

        return START_NOT_STICKY;
    }

    /**
     * 接收歌曲列表并开始播放。
     */
    private void playPlaylist(ArrayList<String> files, int index) {

        if (files == null || files.isEmpty()) {
            return;
        }

        playlist = new ArrayList<>(files);

        playAt(index);
    }
    /**
     * 设置 播放索引
     */
    public void setCurrentSongIndex(int index)
    {
        currentIndex=index;
    }
    /**
     * 播放指定歌曲。
     */
    private void playAt(int index) {

        if (index < 0 || index >= playlist.size()) {
            return;
        }

        currentIndex = index;
        currentPath = playlist.get(index);

        // 保存最近播放歌曲
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PLAYED, currentPath)
                .apply();

        releaseMediaPlayer();

        prepared = false;
        preparing = true;

        updateMetadata();
        updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING);

        // 在准备音频时就启动前台服务，避免后台启动延迟
        startForegroundNotification();

        try {

            mediaPlayer = new MediaPlayer();

            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

            // 防止切换到其他 App 后 CPU 休眠导致播放中断
            mediaPlayer.setWakeMode(
                    getApplicationContext(),
                    PowerManager.PARTIAL_WAKE_LOCK
            );

            mediaPlayer.setDataSource(currentPath);

            mediaPlayer.setOnPreparedListener(mp -> {

                if (mediaPlayer != mp) {
                    return;
                }

                prepared = true;
                preparing = false;

                mp.start();

                updateMetadata();
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                updateNotification();

                notifyPlaybackChanged(false);
            });

            mediaPlayer.setOnCompletionListener(mp -> {

                if (mediaPlayer != mp) {
                    return;
                }

                playNext();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {

                prepared = false;
                preparing = false;

                releaseMediaPlayer();

                updatePlaybackState(PlaybackStateCompat.STATE_ERROR);

                stopForegroundNotification();

                notifyPlaybackChanged(false);

                return true;
            });

            mediaPlayer.prepareAsync();

        } catch (Exception e) {

            prepared = false;
            preparing = false;

            releaseMediaPlayer();

            updatePlaybackState(PlaybackStateCompat.STATE_ERROR);

            stopForegroundNotification();

            notifyPlaybackChanged(false);
        }

        notifyPlaybackChanged(false);
    }

    /**
     * 播放或暂停。
     */
    private void togglePlayback() {

        if (mediaPlayer == null) {

            if (currentIndex >= 0 && currentIndex < playlist.size()) {
                playAt(currentIndex);
            }

            return;
        }

        if (preparing || !prepared) {
            return;
        }

        if (mediaPlayer.isPlaying()) {
            pausePlayback();
        } else {
            resumePlayback();
        }
    }

    /**
     * 暂停。
     */
    private void pausePlayback() {

        if (mediaPlayer == null || !prepared) {
            return;
        }

        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }

        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        updateNotification();

        notifyPlaybackChanged(false);
    }

    /**
     * 继续播放。
     */
    private void resumePlayback() {

        if (mediaPlayer == null) {

            if (currentIndex >= 0) {
                playAt(currentIndex);
            }

            return;
        }

        if (!prepared || preparing) {
            return;
        }

        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }

        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        updateNotification();

        notifyPlaybackChanged(false);
    }

    /**
     * 上一首。
     */
    private void playPrevious() {

        if (playlist.isEmpty()) {
            return;
        }

        if (currentIndex <= 0) {
            playAt(0);
        } else {
            playAt(currentIndex - 1);
        }
    }

    /**
     * 下一首。
     * 最后一首播放完毕后停止，不循环。
     */
    private void playNext() {

        if (playlist.isEmpty()) {
            return;
        }

        if (currentIndex >= playlist.size() - 1) {

            finishPlayback();

        } else {

            playAt(currentIndex + 1);
        }
    }

    /**
     * 播放结束。
     */
    private void finishPlayback() {

        releaseMediaPlayer();

        prepared = false;
        preparing = false;

        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);

        stopForegroundNotification();

        notifyPlaybackChanged(true);
    }

    /**
     * 拖动进度。
     */
    private void seekTo(int position) {

        if (mediaPlayer == null || !prepared) {
            return;
        }

        try {

            int duration = mediaPlayer.getDuration();

            position = Math.max(0, Math.min(position, duration));

            mediaPlayer.seekTo(position);

            updatePlaybackState(
                    mediaPlayer.isPlaying()
                            ? PlaybackStateCompat.STATE_PLAYING
                            : PlaybackStateCompat.STATE_PAUSED
            );

            notifyPlaybackChanged(false);

            updateNotification();

        } catch (Exception ignored) {
        }
    }

    /**
     * 当前是否正在播放。
     */
    private boolean isPlaying() {

        try {
            return mediaPlayer != null &&
                    prepared &&
                    mediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取歌曲总时长。
     */
    private int getDuration() {

        try {
            if (mediaPlayer != null && prepared) {
                return mediaPlayer.getDuration();
            }
        } catch (Exception ignored) {
        }

        return 0;
    }

    /**
     * 获取当前播放位置。
     */
    private int getPosition() {

        try {
            if (mediaPlayer != null && prepared) {
                return mediaPlayer.getCurrentPosition();
            }
        } catch (Exception ignored) {
        }

        return 0;
    }

    /**
     * 更新媒体元数据。
     */
    private void updateMetadata() {

        if (mediaSession == null || currentPath.isEmpty()) {
            return;
        }

        File file = new File(currentPath);

        MediaMetadataCompat metadata =
                new MediaMetadataCompat.Builder()
                        .putString(
                                MediaMetadataCompat.METADATA_KEY_TITLE,
                                file.getName()
                        )
                        .putString(
                                MediaMetadataCompat.METADATA_KEY_ARTIST,
                                file.getParentFile() != null
                                        ? file.getParentFile().getName()
                                        : "我的音乐"
                        )
                        .putLong(
                                MediaMetadataCompat.METADATA_KEY_DURATION,
                                getDuration()
                        )
                        .build();

        mediaSession.setMetadata(metadata);
    }

    /**
     * 更新系统媒体会话播放状态。
     * Android 系统媒体控制通过这个状态获取播放进度。
     */
    private void updatePlaybackState(int state) {

        if (mediaSession == null) {
            return;
        }

        long actions =
                PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SEEK_TO |
                        PlaybackStateCompat.ACTION_STOP;

        PlaybackStateCompat playbackState =
                new PlaybackStateCompat.Builder()
                        .setActions(actions)
                        .setState(
                                state,
                                getPosition(),
                                isPlaying() ? 1.0f : 0.0f
                        )
                        .build();

        mediaSession.setPlaybackState(playbackState);
    }

    /**
     * 启动前台通知。
     */
    private void startForegroundNotification() {

        Notification notification = buildNotification();

        startForeground(NOTIFICATION_ID, notification);

        foregroundStarted = true;
    }

    /**
     * 更新通知栏。
     */
    private void updateNotification() {

        if (!foregroundStarted) {
            startForegroundNotification();
            return;
        }

        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    /**
     * 构建通知栏媒体控制。
     */

    /**
     * 构建自定义通知栏媒体控制。
     * 显示歌曲名称、播放进度、当前时间、总时长和控制按钮。
     */
    private Notification buildNotification() {

        boolean playing = isPlaying();

        int duration = getDuration();
        int position = getPosition();

        String title = currentPath.isEmpty()
                ? "我的音乐"
                : new File(currentPath).getName();

        String subtitle = preparing ? "正在加载..." :
                playing ? "正在播放" : "已暂停";

        // 加载自定义通知栏布局
        RemoteViews views = new RemoteViews(
                getPackageName(),
                R.layout.notification_music
        );

        views.setTextViewText(R.id.notificationTitle, title);
        views.setTextViewText(R.id.notificationSubtitle, subtitle);

        // 设置进度条，使用 0～1000 的进度范围
        int progress = duration > 0
                ? (int) ((long) position * 1000 / duration)
                : 0;

        views.setProgressBar(
                R.id.notificationProgress,
                1000,
                progress,
                duration <= 0
        );

        views.setTextViewText(
                R.id.notificationCurrentTime,
                formatTime(position)
        );

        views.setTextViewText(
                R.id.notificationTotalTime,
                formatTime(duration)
        );

        // 根据播放状态切换图标
        views.setImageViewResource(
                R.id.notificationPlayPause,
                playing
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play
        );

        // 绑定三个通知栏控制按钮
        views.setOnClickPendingIntent(
                R.id.notificationPrevious,
                createServicePendingIntent(ACTION_PREVIOUS, 101)
        );

        views.setOnClickPendingIntent(
                R.id.notificationPlayPause,
                createServicePendingIntent(ACTION_PLAY_PAUSE, 102)
        );

        views.setOnClickPendingIntent(
                R.id.notificationNext,
                createServicePendingIntent(ACTION_NEXT, 103)
        );

        // 点击通知主体返回播放器
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this,
                100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(subtitle)
                        .setContentIntent(contentPendingIntent)
                        .setContent(views)
                        .setCustomBigContentView(views)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOnlyAlertOnce(true)
                        .setOngoing(playing || preparing)
                        .setStyle(
                                new MediaStyle()
                                        .setMediaSession(mediaSession.getSessionToken())
                        );

        return builder.build();
    }

    /**
     * 毫秒转换为 mm:ss
     */
    private String formatTime(long milliseconds) {

        long seconds = Math.max(0, milliseconds) / 1000;

        return String.format(
                java.util.Locale.getDefault(),
                "%02d:%02d",
                seconds / 60,
                seconds % 60
        );
    }


    /**
     * 创建通知栏控制指令。
     */
    private PendingIntent createServicePendingIntent(
            String action,
            int requestCode) {

        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(action);

        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    /**
     * 停止前台通知。
     */
    private void stopForegroundNotification() {

        if (foregroundStarted) {

            stopForeground(true);

            foregroundStarted = false;
        }

        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        manager.cancel(NOTIFICATION_ID);
    }

    /**
     * 每秒同步播放进度。
     * Activity 只更新底部播放控件，不刷新歌曲列表。
     */
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {

            if (prepared) {

                updatePlaybackState(
                        isPlaying()
                                ? PlaybackStateCompat.STATE_PLAYING
                                : PlaybackStateCompat.STATE_PAUSED
                );

                updateNotification();

                // 恢复每秒回调，让 Activity 的进度条同步更新
                notifyPlaybackChanged(false);
            }

            handler.postDelayed(this, 1000);
        }
    };

    /**
     * 通知 Activity 播放状态发生变化。
     */
    private void notifyPlaybackChanged(boolean completed) {

        if (playbackListener == null) {
            return;
        }

        playbackListener.onPlaybackChanged(
                currentPath,
                currentIndex,
                getDuration(),
                getPosition(),
                isPlaying(),
                preparing,
                completed
        );
    }

    /**
     * 释放 MediaPlayer。
     */
    private void releaseMediaPlayer() {

        if (mediaPlayer != null) {

            try {

                mediaPlayer.setOnCompletionListener(null);
                mediaPlayer.setOnPreparedListener(null);
                mediaPlayer.setOnErrorListener(null);

                mediaPlayer.reset();
                mediaPlayer.release();

            } catch (Exception ignored) {
            }

            mediaPlayer = null;
        }

        prepared = false;
        preparing = false;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户移除任务时，不停止正在播放的音乐
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {

        handler.removeCallbacks(progressRunnable);

        releaseMediaPlayer();

        stopForegroundNotification();

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }

        super.onDestroy();
    }
}
