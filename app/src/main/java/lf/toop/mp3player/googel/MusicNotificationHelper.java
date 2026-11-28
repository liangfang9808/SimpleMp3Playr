package lf.toop.mp3player.googel;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import lf.toop.mp3player.R;

public class MusicNotificationHelper {

    public static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_PREV = "lf.toop.mp3player.ACTION_PREV";
    public static final String ACTION_PLAY_PAUSE = "lf.toop.mp3player.ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT = "lf.toop.mp3player.ACTION_NEXT";
    public static final String ACTION_CLOSE = "lf.toop.mp3player.ACTION_CLOSE";

    public static Notification buildNotification(
            Context context,
            String songTitle,
            String artist,
            Bitmap coverBitmap,
            boolean isPlaying,
            int currentProgress,
            int maxProgress) {

        RemoteViews collapsedView = new RemoteViews(
                context.getPackageName(), R.layout.layout_music_collapsed);
        RemoteViews expandedView = new RemoteViews(
                context.getPackageName(), R.layout.layout_music_expanded);

        int playPauseIcon = isPlaying
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;

        bindViews(context, collapsedView, songTitle, artist, coverBitmap,
                playPauseIcon, currentProgress, maxProgress, false);
        bindViews(context, expandedView, songTitle, artist, coverBitmap,
                playPauseIcon, currentProgress, maxProgress, true);

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                context, 100, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(context)
                .setSmallIcon(R.mipmap.ic_launcher) // Android 6.0 真机建议替换为纯白透明轮廓图标
                .setContentIntent(contentPendingIntent)
                .setCustomContentView(collapsedView)
                .setCustomBigContentView(expandedView)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(isPlaying)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private static void bindViews(
            Context context,
            RemoteViews views,
            String songTitle,
            String artist,
            Bitmap coverBitmap,
            int playPauseIcon,
            int currentProgress,
            int maxProgress,
            boolean hasCloseButton) {

        views.setTextViewText(R.id.tv_song_title, songTitle);
        views.setTextViewText(R.id.tv_artist, artist);

        if (coverBitmap != null && !coverBitmap.isRecycled()) {
            views.setImageViewBitmap(R.id.iv_cover, coverBitmap);
        } else {
            views.setImageViewResource(R.id.iv_cover, R.mipmap.ic_launcher);
        }

        views.setImageViewResource(R.id.btn_play_pause, playPauseIcon);
        views.setProgressBar(R.id.pb_music_progress, maxProgress, currentProgress, false);

        views.setOnClickPendingIntent(R.id.btn_prev,
                createServiceIntent(context, ACTION_PREV, 1));
        views.setOnClickPendingIntent(R.id.btn_play_pause,
                createServiceIntent(context, ACTION_PLAY_PAUSE, 2));
        views.setOnClickPendingIntent(R.id.btn_next,
                createServiceIntent(context, ACTION_NEXT, 3));

        if (hasCloseButton) {
            views.setOnClickPendingIntent(R.id.btn_close,
                    createServiceIntent(context, ACTION_CLOSE, 4));
        }
    }

    private static PendingIntent createServiceIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, MusicPlayerService.class);
        intent.setAction(action);
        return PendingIntent.getService(
                context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }
}