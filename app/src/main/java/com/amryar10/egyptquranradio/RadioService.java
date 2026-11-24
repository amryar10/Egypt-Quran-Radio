package com.amryar10.egyptquranradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class RadioService extends Service {
    public static final String ACTION_PLAY = "com.amryar10.egyptquranradio.PLAY";
    public static final String ACTION_STOP = "com.amryar10.egyptquranradio.STOP";
    public static final String ACTION_SET_VOLUME = "com.amryar10.egyptquranradio.SET_VOLUME";
    public static final String EXTRA_VOLUME = "volume";

    public static final String ACTION_STATE_CHANGED = "com.amryar10.egyptquranradio.STATE_CHANGED";
    public static final String EXTRA_STATE = "state";

    private static final String CHANNEL_ID = "egypt_quran_radio_playback";
    private static final int NOTIFICATION_ID = 1001;

    private static final String STREAM_URL_PRIMARY =
            "https://n01.radiojar.com/8s5u5tpdtwzuv?rj-ttl=5&rj-tok=AAABogLTvj0A2EP6CmJfvDTvnA";
    private static final String STREAM_URL_FALLBACK =
            "https://stream.radiojar.com/8s5u5tpdtwzuv";

    public static volatile String currentState = "idle";
    public static volatile float currentVolume = 0.85f;

    private MediaPlayer mediaPlayer;
    private WifiManager.WifiLock wifiLock;
    private boolean triedFallback = false;
    private boolean isStopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EgyptQuranRadio:WifiLock");
            wifiLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            isStopping = false;
            triedFallback = false;
            startForegroundWithNotification("Connecting...");
            startStream(STREAM_URL_PRIMARY);
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback("stopped");
        } else if (ACTION_SET_VOLUME.equals(action)) {
            currentVolume = intent.getFloatExtra(EXTRA_VOLUME, currentVolume);
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.setVolume(currentVolume, currentVolume);
                } catch (IllegalStateException ignored) {}
            }
        }
        return START_STICKY;
    }

    private void startStream(String url) {
        releasePlayer();
        updateState("loading");
        updateNotification("Connecting...");

        if (wifiLock != null && !wifiLock.isHeld()) {
            try {
                wifiLock.acquire();
            } catch (Exception ignored) {}
        }

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());

        mediaPlayer.setOnPreparedListener(mp -> {
            if (isStopping) return;
            try {
                mp.setVolume(currentVolume, currentVolume);
                mp.start();
                updateState("live");
                updateNotification("Playing Live");
            } catch (Exception e) {
                handleStreamError();
            }
        });

        mediaPlayer.setOnInfoListener((mp, what, extra) -> {
            if (isStopping) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                updateState("loading");
                updateNotification("Buffering...");
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                if (mp.isPlaying()) {
                    updateState("live");
                    updateNotification("Playing Live");
                }
            }
            return false;
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            handleStreamError();
            return true;
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            if (!isStopping) {
                handleStreamError();
            }
        });

        try {
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            handleStreamError();
        }
    }

    private void handleStreamError() {
        if (isStopping) return;
        if (!triedFallback) {
            triedFallback = true;
            startStream(STREAM_URL_FALLBACK);
        } else {
            stopPlayback("error");
        }
    }

    private void stopPlayback(String finalState) {
        isStopping = true;
        releasePlayer();
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {}
        }
        updateState(finalState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
            } catch (Exception ignored) {}
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private void updateState(String state) {
        currentState = state;
        Intent broadcast = new Intent(ACTION_STATE_CHANGED);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_STATE, state);
        sendBroadcast(broadcast);
    }

    private void startForegroundWithNotification(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String statusText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, RadioService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("Egypt Quran Radio")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Stop", stopPending).build());

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Egypt Quran Radio Playback",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps live radio playing in the background");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
