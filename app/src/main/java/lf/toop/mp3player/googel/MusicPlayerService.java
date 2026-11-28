package lf.toop.mp3player.googel;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MusicPlayerService extends Service {

    public static final String ACTION_PLAY_LIST = "lf.toop.mp3player.ACTION_PLAY_LIST";
    public static final String ACTION_TOGGLE_PLAY = "lf.toop.mp3player.ACTION_TOGGLE_PLAY";
    public static final String ACTION_PREV = MusicNotificationHelper.ACTION_PREV;
    public static final String ACTION_PLAY_PAUSE = MusicNotificationHelper.ACTION_PLAY_PAUSE;
    public static final String ACTION_NEXT = MusicNotificationHelper.ACTION_NEXT;
    public static final String ACTION_CLOSE = MusicNotificationHelper.ACTION_CLOSE;
    public static final String ACTION_SEEK_TO = "lf.toop.mp3player.ACTION_SEEK_TO";

    public static final String EXTRA_PLAYLIST = "extra_playlist";
    public static final String EXTRA_INDEX = "extra_index";
    public static final String EXTRA_SEEK_POS = "extra_seek_pos";

    public static final String ACTION_STATE_CHANGED = "lf.toop.mp3player.ACTION_STATE_CHANGED";
    public static final String EXTRA_PATH = "extra_path";
    public static final String EXTRA_INDEX_OUT = "extra_index_out";
    public static final String EXTRA_DURATION = "extra_duration";
    public static final String EXTRA_POSITION = "extra_position";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";
    public static final String EXTRA_IS_PREPARING = "extra_is_preparing";
    public static final String EXTRA_COMPLETED = "extra_completed";

    private static final long PROGRESS_INTERVAL_MS = 1000L;
    private static final int MAX_PROGRESS = 1000;

    private final IBinder binder = new LocalBinder();
    private final Handler progressHandler = new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
    private final List<String> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private String currentPath = "";
    private String currentTitle = "未播放歌曲";
    private String currentArtist = "未知歌手";
    private Bitmap currentCover;

    private boolean isPlaying = false;
    private boolean isPreparing = false;
    private boolean isCompleted = false;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && mediaPlayer != null && !isPreparing) {
                updateNotificationAndBroadcast(false);
                progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
            }
        }
    };

    public class LocalBinder extends Binder {
        public MusicPlayerService getService() {
            return MusicPlayerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initMediaPlayer();
    }

    private void initMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(mp -> {
            isPreparing = false;
            isCompleted = false;
            mp.start();
            isPlaying = true;
            startForegroundWithCurrentState();
            startProgressUpdates();
            broadcastState(false);
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            isPlaying = false;
            stopProgressUpdates();
            if (!playlist.isEmpty() && currentIndex < playlist.size() - 1) {
                playAtIndex(currentIndex + 1);
            } else {
                isCompleted = true;
                updateNotificationAndBroadcast(true);
            }
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            isPlaying = false;
            isPreparing = false;
            stopProgressUpdates();
            broadcastState(false);
            return true;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        switch (action) {
            case ACTION_PLAY_LIST:
                ArrayList<String> list = intent.getStringArrayListExtra(EXTRA_PLAYLIST);
                int index = intent.getIntExtra(EXTRA_INDEX, 0);
                if (list != null && !list.isEmpty()) {
                    playlist.clear();
                    playlist.addAll(list);
                    playAtIndex(index);
                }
                break;

            case ACTION_PLAY_PAUSE:
            case ACTION_TOGGLE_PLAY:
                togglePlayPause();
                break;

            case ACTION_PREV:
                playPrevious();
                break;

            case ACTION_NEXT:
                playNext();
                break;

            case ACTION_SEEK_TO:
                int pos = intent.getIntExtra(EXTRA_SEEK_POS, 0);
                seekTo(pos);
                break;

            case ACTION_CLOSE:
                stopAndCloseService();
                break;
        }

        return START_NOT_STICKY;
    }

    public void playAtIndex(int index) {
        if (index < 0 || index >= playlist.size() || mediaPlayer == null) {
            return;
        }

        currentIndex = index;
        currentPath = playlist.get(index);
        File file = new File(currentPath);
        currentTitle = file.getName();
        currentArtist = extractArtist(currentPath);
        currentCover = extractCover(currentPath, 160);

        stopProgressUpdates();
        isPlaying = false;
        isPreparing = true;
        isCompleted = false;

        // 切歌瞬间先将进度重置为 0，避免显示上一首残留进度
        pushNotification(0, MAX_PROGRESS, false);
        broadcastState(false);

        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(currentPath);
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            isPreparing = false;
            broadcastState(false);
        }
    }

    public void togglePlayPause() {
        if (mediaPlayer == null) {
            return;
        }
        if (isPreparing) {
            return;
        }
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            stopProgressUpdates();
            updateNotificationAndBroadcast(false);
        } else if (!currentPath.isEmpty()) {
            if (isCompleted) {
                playAtIndex(Math.max(0, currentIndex));
                return;
            }
            mediaPlayer.start();
            isPlaying = true;
            isCompleted = false;
            startForegroundWithCurrentState();
            startProgressUpdates();
            broadcastState(false);
        }
    }

    public void playPrevious() {
        if (playlist.isEmpty()) {
            return;
        }
        int targetIndex = currentIndex > 0 ? currentIndex - 1 : 0;
        playAtIndex(targetIndex);
    }

    public void playNext() {
        if (playlist.isEmpty()) {
            return;
        }
        if (currentIndex < playlist.size() - 1) {
            playAtIndex(currentIndex + 1);
        }
    }

    public void seekTo(int positionMs) {
        if (mediaPlayer != null && !isPreparing && mediaPlayer.getDuration() > 0) {
            int safePos = Math.max(0, Math.min(positionMs, mediaPlayer.getDuration()));
            mediaPlayer.seekTo(safePos);
            updateNotificationAndBroadcast(false);
        }
    }

    public void stopAndCloseService() {
        isPlaying = false;
        isPreparing = false;
        stopProgressUpdates();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.reset();
        }
        stopForeground(true);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(MusicNotificationHelper.NOTIFICATION_ID);
        }
        broadcastState(false);
        stopSelf();
    }

    private void startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS);
    }

    private void stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable);
    }

    private void startForegroundWithCurrentState() {
        int progress = calcProgress();
        Notification notification = MusicNotificationHelper.buildNotification(
                this, currentTitle, currentArtist, currentCover,
                isPlaying, progress, MAX_PROGRESS);
        startForeground(MusicNotificationHelper.NOTIFICATION_ID, notification);
    }

    private void updateNotificationAndBroadcast(boolean completed) {
        int progress = completed ? MAX_PROGRESS : calcProgress();
        pushNotification(progress, MAX_PROGRESS, completed);
        broadcastState(completed);
    }

    private void pushNotification(int progress, int max, boolean completed) {
        int displayProgress = completed ? max : progress;
        Notification notification = MusicNotificationHelper.buildNotification(
                this, currentTitle, currentArtist, currentCover,
                isPlaying, displayProgress, max);

        if (isPlaying) {
            startForeground(MusicNotificationHelper.NOTIFICATION_ID, notification);
        } else {
            stopForeground(false);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(MusicNotificationHelper.NOTIFICATION_ID, notification);
            }
        }
    }

    private int calcProgress() {
        if (mediaPlayer == null || isPreparing) {
            return 0;
        }
        int duration = mediaPlayer.getDuration();
        if (duration <= 0) {
            return 0;
        }
        int pos = Math.max(0, Math.min(mediaPlayer.getCurrentPosition(), duration));
        System.out.println("pos"+"="+pos);
        return (int) ((pos * (long) MAX_PROGRESS) / duration);
    }

    public void broadcastState(boolean completed) {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_PATH, currentPath);
        intent.putExtra(EXTRA_INDEX_OUT, currentIndex);
        intent.putExtra(EXTRA_DURATION, getDuration());
        intent.putExtra(EXTRA_POSITION, getPosition());
        intent.putExtra(EXTRA_IS_PLAYING, isPlaying);
        intent.putExtra(EXTRA_IS_PREPARING, isPreparing);
        intent.putExtra(EXTRA_COMPLETED, completed || isCompleted);
        sendBroadcast(intent);
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getDuration() {
        if (mediaPlayer == null || isPreparing) {
            return 0;
        }
        try {
            return Math.max(0, mediaPlayer.getDuration());
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int getPosition() {
        if (mediaPlayer == null || isPreparing) {
            return 0;
        }
        try {
            return Math.max(0, mediaPlayer.getCurrentPosition());
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isPreparing() {
        return isPreparing;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    private String extractArtist(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            return (artist != null && !artist.trim().isEmpty()) ? artist : "未知歌手";
        } catch (Exception e) {
            return "未知歌手";
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private Bitmap extractCover(String path, int maxSize) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture == null || picture.length == 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(picture, 0, picture.length, opts);

            int sampleSize = 1;
            while (opts.outWidth / sampleSize > maxSize || opts.outHeight / sampleSize > maxSize) {
                sampleSize *= 2;
            }
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sampleSize;
            return BitmapFactory.decodeByteArray(picture, 0, picture.length, decodeOpts);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        stopProgressUpdates();
        progressHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}