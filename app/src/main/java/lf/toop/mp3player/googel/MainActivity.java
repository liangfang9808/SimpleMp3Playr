package lf.toop.mp3player.googel;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lf.toop.mp3player.R;
import lf.toop.mp3player.googel.MusicPlayerService;

public class MainActivity extends ComponentActivity {

    // 音乐根目录
    private static final String MUSIC_ROOT = "/sdcard/mp3";

    private static final int REQUEST_STORAGE = 1001;

    // 最近播放记录
    private static final String PREFS_NAME = "player_history";
    private static final String KEY_LAST_PLAYED = "last_played_path";

    // 音量
    private AudioManager audioManager;
    private SeekBar seekVolume;
    private TextView txtVolumeValue;
    private TextView btnMute;
    private int previousVolume = -1;

    // 页面控件
    private TextView txtTitle;
    private TextView txtPath;
    private TextView txtStatus;
    private TextView txtPlayingName;
    private TextView txtCurrentTime;
    private TextView txtTotalTime;

    private TextView btnBack;
    private Button btnPlay;
    private Button btnPrevious;
    private Button btnNext;

    private ListView listAudio;
    private SeekBar seekBar;

    // 文件列表
    private File currentDirectory;
    private final List<File> audioFiles = new ArrayList<>();

    private boolean showingSongs = false;
    private boolean hasRestoredLastPlayed = false;

    private AudioAdapter adapter;

    // 后台播放服务
    private MusicPlayerService musicService;
    private boolean serviceBound = false;
    private boolean serviceBindRequested = false;
    private boolean receiverRegistered = false;

    // 当前播放歌曲在列表中的索引
    private int currentSongIndex = -1;
    // 上一次同步的播放路径，用于判断是否真的切换了歌曲
    private String lastUiPlaybackPath = "";
    // 服务尚未连接时暂存播放请求
    private ArrayList<String> pendingPlaylist;
    private int pendingPlayIndex = -1;

    // 用户是否正在拖动进度条
    private boolean userSeeking = false;

    /**
     * 监听 Service 播放状态变化（包含通知栏点击上一曲/下一曲/播放暂停/关闭）。
     */
    private final BroadcastReceiver playbackStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !MusicPlayerService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            updateUiFromService(
                    intent.getStringExtra(MusicPlayerService.EXTRA_PATH),
                    intent.getIntExtra(MusicPlayerService.EXTRA_INDEX_OUT, -1),
                    intent.getIntExtra(MusicPlayerService.EXTRA_DURATION, 0),
                    intent.getIntExtra(MusicPlayerService.EXTRA_POSITION, 0),
                    intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false),
                    intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PREPARING, false),
                    intent.getBooleanExtra(MusicPlayerService.EXTRA_COMPLETED, false)
            );
        }
    };

    /**
     * Service 连接管理。
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicPlayerService.LocalBinder binder = (MusicPlayerService.LocalBinder) service;
            musicService = binder.getService();
            serviceBound = true;

            if (pendingPlaylist != null && pendingPlayIndex >= 0) {
                startPlaylistService(pendingPlaylist, pendingPlayIndex);
                pendingPlaylist = null;
                pendingPlayIndex = -1;
            } else {
                refreshFromService();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            musicService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 物理音量键控制系统媒体音量
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        initViews();
        initEvents();
        initVolumeControl();
        initBackPressed();
        checkStoragePermission();

        System.out.println("google");
    }

    /**
     * 页面进入前台时注册广播并绑定播放服务。
     */
    @Override
    protected void onStart() {
        super.onStart();

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(MusicPlayerService.ACTION_STATE_CHANGED);
            registerReceiver(playbackStateReceiver, filter);
            receiverRegistered = true;
        }

        Intent intent = new Intent(this, MusicPlayerService.class);
        serviceBindRequested = bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );
    }

    /**
     * 页面离开前台时解绑服务并注销广播。
     * 注意：这里不停止 Service，不释放 MediaPlayer。
     */
    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(playbackStateReceiver);
            receiverRegistered = false;
        }

        if (serviceBindRequested) {
            unbindService(serviceConnection);
            serviceBindRequested = false;
        }

        serviceBound = false;
        musicService = null;

        super.onStop();
    }

    /**
     * 初始化页面控件。
     */
    private void initViews() {
        txtTitle = findViewById(R.id.txtTitle);
        txtPath = findViewById(R.id.txtPath);
        txtStatus = findViewById(R.id.txtStatus);

        txtPlayingName = findViewById(R.id.txtPlayingName);
        txtCurrentTime = findViewById(R.id.txtCurrentTime);
        txtTotalTime = findViewById(R.id.txtTotalTime);

        btnBack = findViewById(R.id.btnBack);
        btnPlay = findViewById(R.id.btnPlay);
        btnPrevious = findViewById(R.id.btnPrevious);
        btnNext = findViewById(R.id.btnNext);

        listAudio = findViewById(R.id.listAudio);
        seekBar = findViewById(R.id.seekBar);

        seekBar.setMax(1000);

        adapter = new AudioAdapter();
        listAudio.setAdapter(adapter);

        seekVolume = findViewById(R.id.seekVolume);
        txtVolumeValue = findViewById(R.id.txtVolumeValue);
        btnMute = findViewById(R.id.btnMute);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    /**
     * 初始化按钮事件。
     */
    private void initEvents() {
        btnBack.setOnClickListener(v -> goBack());

        btnPlay.setOnClickListener(v -> togglePlayback());

        btnPrevious.setOnClickListener(v -> playPrevious());

        btnNext.setOnClickListener(v -> playNext());

        // 播放进度拖动
        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar bar,
                            int progress,
                            boolean fromUser) {
                        if (fromUser) {
                            userSeeking = true;
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        userSeeking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        userSeeking = false;

                        if (serviceBound && musicService != null) {
                            int duration = musicService.getDuration();
                            int position = (int) (duration * (bar.getProgress() / 1000.0));
                            musicService.seekTo(position);
                        } else {
                            Intent seekIntent = new Intent(MainActivity.this, MusicPlayerService.class);
                            seekIntent.setAction(MusicPlayerService.ACTION_SEEK_TO);
                            seekIntent.putExtra(MusicPlayerService.EXTRA_SEEK_POS, bar.getProgress());
                            startService(seekIntent);
                        }
                    }
                });

        // 点击文件夹或歌曲
        listAudio.setOnItemClickListener((parent, view, position, id) -> {
            if (!showingSongs) {
                loadMp3Files(audioFiles.get(position));
            } else {
                playFile(position);
            }
        });
    }

    /**
     * 检查存储读取权限。
     */
    private void checkStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            showDirectories();
            return;
        }

        int permission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
        );

        if (permission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showDirectories();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    REQUEST_STORAGE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_STORAGE) {
            if (grantResults.length > 0 &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showDirectories();
            } else {
                txtStatus.setText("没有获得存储读取权限");
                Toast.makeText(
                        this,
                        "请允许读取存储权限",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    /**
     * 显示音乐根目录。
     */
    private void showDirectories() {
        File root = new File(MUSIC_ROOT);

        showingSongs = false;
        currentDirectory = null;

        txtTitle.setText("我的音乐");
        txtPath.setText(MUSIC_ROOT);

        btnBack.setVisibility(View.GONE);

        audioFiles.clear();

        if (!root.exists() || !root.isDirectory()) {
            adapter.notifyDataSetChanged();
            txtStatus.setText("音乐目录不存在");
            return;
        }

        File[] directories = root.listFiles(File::isDirectory);

        if (directories == null || directories.length == 0) {
            adapter.notifyDataSetChanged();
            txtStatus.setText("暂无音乐文件夹");
            return;
        }

        Arrays.sort(directories, (a, b) ->
                naturalCompare(a.getName(), b.getName()));

        audioFiles.addAll(Arrays.asList(directories));

        adapter.notifyDataSetChanged();

        txtStatus.setText("共 " + audioFiles.size() + " 个文件夹");

        // 只在第一次加载根目录时恢复最近播放位置
        if (!hasRestoredLastPlayed) {
            hasRestoredLastPlayed = true;
            restoreLastPlayed();
        }
    }

    /**
     * 加载指定文件夹的 MP3 文件。
     */
    private void loadMp3Files(File directory) {
        showingSongs = true;
        currentDirectory = directory;

        txtTitle.setText(directory.getName());
        txtPath.setText(directory.getAbsolutePath());

        btnBack.setVisibility(View.VISIBLE);

        audioFiles.clear();

        File[] files = directory.listFiles(file ->
                file.isFile() &&
                        file.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")
        );

        if (files != null) {
            Arrays.sort(files, (a, b) ->
                    naturalCompare(a.getName(), b.getName()));
            audioFiles.addAll(Arrays.asList(files));
        }

        currentSongIndex = -1;

        String lastPath = getLastPlayedPath();

        for (int i = 0; i < audioFiles.size(); i++) {
            if (audioFiles.get(i).getAbsolutePath().equals(lastPath)) {
                currentSongIndex = i;
                break;
            }
        }

        adapter.notifyDataSetChanged();

        if (audioFiles.isEmpty()) {
            txtStatus.setText("这个文件夹没有 MP3 文件");
        } else {
            txtStatus.setText("共 " + audioFiles.size() + " 首歌曲");

            if (currentSongIndex >= 0) {
                File lastFile = audioFiles.get(currentSongIndex);
                txtPlayingName.setText(lastFile.getName());
                btnPlay.setText("继续播放");
                txtStatus.setText("已恢复上次播放记录");
                listAudio.setSelection(currentSongIndex);
            }
        }
    }

    /**
     * 点击歌曲，交给后台 MusicPlayerService 播放并刷新通知栏控制栏。
     */
    private void playFile(int index) {
        if (index < 0 || index >= audioFiles.size()) {
            return;
        }

        File file = audioFiles.get(index);

        currentSongIndex = index;

        txtPlayingName.setText(file.getName());
        txtCurrentTime.setText("00:00");
        txtTotalTime.setText("00:00");

        seekBar.setProgress(0);

        btnPlay.setText("准备中");
        txtStatus.setText("正在加载：" + file.getName());

        ArrayList<String> paths = new ArrayList<>();
        for (File audioFile : audioFiles) {
            paths.add(audioFile.getAbsolutePath());
        }

        if (serviceBound && musicService != null) {
            startPlaylistService(paths, index);
        } else {
            pendingPlaylist = paths;
            pendingPlayIndex = index;
            startPlaylistService(paths, index);
        }

        saveLastPlayed(file);
        adapter.notifyDataSetChanged();
    }

    private void startPlaylistService(ArrayList<String> paths, int index) {
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        serviceIntent.setAction(MusicPlayerService.ACTION_PLAY_LIST);
        serviceIntent.putStringArrayListExtra(MusicPlayerService.EXTRA_PLAYLIST, paths);
        serviceIntent.putExtra(MusicPlayerService.EXTRA_INDEX, index);
        startService(serviceIntent);
    }

    /**
     * 播放 / 暂停。
     */
    private void togglePlayback() {
        if (serviceBound && musicService != null) {
            String servicePath = musicService.getCurrentPath();

            if (pathIsEmpty(servicePath)) {
                if (showingSongs && !audioFiles.isEmpty()) {
                    int index = currentSongIndex >= 0 && currentSongIndex < audioFiles.size()
                            ? currentSongIndex : 0;
                    playFile(index);
                } else {
                    Toast.makeText(this, "请先进入歌曲文件夹", Toast.LENGTH_SHORT).show();
                }
            } else {
                musicService.togglePlayPause();
            }
            return;
        }

        if (showingSongs && !audioFiles.isEmpty()) {
            int index = currentSongIndex >= 0 && currentSongIndex < audioFiles.size()
                    ? currentSongIndex : 0;
            playFile(index);
        } else {
            Toast.makeText(this, "请先进入歌曲文件夹", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 上一首。
     */
    private void playPrevious() {
        if (serviceBound && musicService != null) {
            musicService.playPrevious();
        } else if (showingSongs && !audioFiles.isEmpty()) {
            playFile(Math.max(0, currentSongIndex - 1));
        }
    }

    /**
     * 下一首。
     */
    private void playNext() {
        if (serviceBound && musicService != null) {
            musicService.playNext();
        } else if (showingSongs && !audioFiles.isEmpty()) {
            if (currentSongIndex < audioFiles.size() - 1) {
                playFile(currentSongIndex + 1);
            }
        }
    }

    /**
     * 从 Service 同步当前播放信息。
     */
    private void refreshFromService()
    {
        if (!serviceBound || musicService == null) {
            return;
        }

        String path = musicService.getCurrentPath();
        if (path == null || path.isEmpty()) {
            return;
        }

        updateUiFromService(
                path,
                musicService.getCurrentIndex(),
                musicService.getDuration(),
                musicService.getPosition(),
                musicService.isPlaying(),
                musicService.isPreparing(),
                musicService.isCompleted()
        );
    }

    /**
     * 更新界面。
     */
    private void updateUiFromService(
            String path,
            int index,
            int duration,
            int position,
            boolean playing,
            boolean preparing,
            boolean completed) {

        boolean validPath = path != null && !path.isEmpty();
        boolean songChanged = validPath && !path.equals(lastUiPlaybackPath);

        if (validPath) {
            txtPlayingName.setText(new File(path).getName());
            saveLastPlayed(new File(path));

            if (songChanged) {
                lastUiPlaybackPath = path;

                if (showingSongs) {
                    int newIndex = -1;
                    for (int i = 0; i < audioFiles.size(); i++) {
                        if (audioFiles.get(i).getAbsolutePath().equals(path)) {
                            newIndex = i;
                            break;
                        }
                    }

                    if (newIndex != currentSongIndex) {
                        currentSongIndex = newIndex;
                        adapter.notifyDataSetChanged();
                    }
                }
            }
        }

        if (!userSeeking && duration > 0) {
            int progress = (int) (position * 1000.0 / duration);
            seekBar.setProgress(progress);
        }

        txtCurrentTime.setText(formatTime(position));
        txtTotalTime.setText(formatTime(duration));

        if (preparing) {
            btnPlay.setText("准备中");
            txtStatus.setText("正在加载歌曲");
        } else if (completed) {
            btnPlay.setText("播放");
            txtStatus.setText("播放列表已结束");
            seekBar.setProgress(1000);
        } else if (playing) {
            btnPlay.setText("暂停");
            txtStatus.setText("正在播放");
        } else if (validPath) {
            btnPlay.setText("继续");
            txtStatus.setText("已暂停");
        } else {
            btnPlay.setText("播放");
            txtStatus.setText("已停止");
            txtCurrentTime.setText("00:00");
            txtTotalTime.setText("00:00");
            seekBar.setProgress(0);
        }
    }

    private boolean pathIsEmpty(String path) {
        return path == null || path.isEmpty();
    }

    /**
     * 保存最近播放路径。
     */
    private void saveLastPlayed(File file) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PLAYED, file.getAbsolutePath())
                .apply();
    }

    private String getLastPlayedPath() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_LAST_PLAYED, "");
    }

    private void clearLastPlayed() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_PLAYED)
                .apply();
    }

    /**
     * 恢复最近播放歌曲所在目录。
     */
    private void restoreLastPlayed() {
        String path = getLastPlayedPath();

        if (path == null || path.trim().isEmpty()) {
            return;
        }

        File lastFile = new File(path);
        if (!lastFile.exists() || !lastFile.isFile()) {
            clearLastPlayed();
            return;
        }

        File parent = lastFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            clearLastPlayed();
            return;
        }

        File root = new File(MUSIC_ROOT);
        if (parent.getParentFile() == null ||
                !parent.getParentFile().getAbsolutePath().equals(root.getAbsolutePath())) {
            clearLastPlayed();
            return;
        }

        loadMp3Files(parent);
    }

    /**
     * 格式化时间。
     */
    private String formatTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds) / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;

        return String.format(
                Locale.getDefault(),
                "%02d:%02d",
                minutes,
                seconds
        );
    }

    /**
     * 格式化文件大小。
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        }

        if (size < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", size / 1024.0);
        }

        return String.format(
                Locale.US,
                "%.1f MB",
                size / (1024.0 * 1024.0)
        );
    }

    /**
     * 自然排序。
     */
    private int naturalCompare(String s1, String s2) {
        Pattern pattern = Pattern.compile("\\d+|\\D+");

        Matcher m1 = pattern.matcher(s1);
        Matcher m2 = pattern.matcher(s2);

        while (m1.find() && m2.find()) {
            String part1 = m1.group();
            String part2 = m2.group();

            int result;

            if (Character.isDigit(part1.charAt(0)) &&
                    Character.isDigit(part2.charAt(0))) {
                result = new BigInteger(part1).compareTo(new BigInteger(part2));
            } else {
                result = part1.compareToIgnoreCase(part2);
            }

            if (result != 0) {
                return result;
            }
        }

        return s1.compareToIgnoreCase(s2);
    }

    /**
     * 返回文件夹列表。
     */
    private void goBack() {
        if (showingSongs) {
            showDirectories();
        }
    }

    /**
     * 初始化返回键。
     */
    private void initBackPressed() {
        getOnBackPressedDispatcher().addCallback(
                this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (showingSongs) {
                            goBack();
                        } else {
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });
    }

    /**
     * 初始化系统全局音量。
     */
    private void initVolumeControl() {
        if (audioManager == null) {
            return;
        }

        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        seekVolume.setMax(maxVolume);
        seekVolume.setProgress(currentVolume);

        updateVolumeText(currentVolume, maxVolume);

        seekVolume.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar bar,
                            int progress,
                            boolean fromUser) {
                        if (fromUser) {
                            audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    progress,
                                    0
                            );
                            updateVolumeText(progress, maxVolume);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                    }
                });

        // 静音切换
        btnMute.setOnClickListener(v -> {
            int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

            if (volume > 0) {
                previousVolume = volume;
                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        0,
                        0
                );
            } else {
                int restoreVolume = previousVolume > 0
                        ? previousVolume
                        : Math.max(1, maxVolume / 2);
                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        restoreVolume,
                        0
                );
            }

            refreshVolumeControl();
        });
    }

    private void updateVolumeText(int volume, int maxVolume) {
        int percent = maxVolume > 0 ? volume * 100 / maxVolume : 0;
        txtVolumeValue.setText(percent + "%");

        if (volume == 0) {
            btnMute.setText("🔇");
        } else if (percent < 40) {
            btnMute.setText("🔈");
        } else {
            btnMute.setText("🔊");
        }
    }

    private void refreshVolumeControl() {
        if (audioManager == null || seekVolume == null) {
            return;
        }

        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        seekVolume.setProgress(currentVolume);
        updateVolumeText(currentVolume, maxVolume);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean result = super.onKeyUp(keyCode, event);

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            seekVolume.postDelayed(this::refreshVolumeControl, 100);
        }

        return result;
    }

    /**
     * 列表适配器。
     */
    private class AudioAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return audioFiles.size();
        }

        @Override
        public Object getItem(int position) {
            return audioFiles.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(
                int position,
                View convertView,
                ViewGroup parent) {

            ViewHolder holder;

            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_audio, parent, false);
                holder = new ViewHolder();
                holder.icon = convertView.findViewById(R.id.txtItemIcon);
                holder.name = convertView.findViewById(R.id.txtItemName);
                holder.info = convertView.findViewById(R.id.txtItemInfo);
                holder.arrow = convertView.findViewById(R.id.txtItemArrow);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            File file = audioFiles.get(position);
            holder.name.setText(file.getName());

            if (!showingSongs) {
                holder.icon.setText("📁");
                holder.icon.setTextColor(0xFF159BCB);
                holder.name.setTextColor(0xFF202833);
                holder.info.setText("文件夹");
                holder.info.setTextColor(0xFF89939F);
                holder.arrow.setVisibility(View.VISIBLE);
                convertView.setBackgroundColor(0xFFFFFFFF);
            } else {
                holder.icon.setText("♫");
                holder.arrow.setVisibility(View.GONE);
                holder.info.setText(formatFileSize(file.length()));

                boolean isLastPlayed = file.getAbsolutePath().equals(getLastPlayedPath());
                if (isLastPlayed) {
                    convertView.setBackgroundColor(0xFFE6F7FC);
                    holder.icon.setTextColor(0xFF159BCB);
                    holder.name.setTextColor(0xFF159BCB);
                    holder.info.setTextColor(0xFF159BCB);
                } else {
                    convertView.setBackgroundColor(0xFFFFFFFF);
                    holder.icon.setTextColor(0xFF89939F);
                    holder.name.setTextColor(0xFF202833);
                    holder.info.setTextColor(0xFF89939F);
                }
            }

            return convertView;
        }

        class ViewHolder {
            TextView icon;
            TextView name;
            TextView info;
            TextView arrow;
        }
    }
}