
package lf.toop.mp3player;
import android.media.AudioManager;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.media.MediaPlayer;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.activity.ComponentActivity;
import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends   ComponentActivity  {

    // 系统全局媒体音量
    private AudioManager audioManager;

    // 音量控制控件
    private SeekBar seekVolume;
    private TextView txtVolumeValue;
    private TextView btnMute;

    // 静音前保存的音量
    private int previousVolume = -1;

    // 音乐根目录
    private static final String MUSIC_ROOT ="/sdcard/mp3";// "/storage/sdcard/mp3/";

    private static final int REQUEST_STORAGE = 1001;

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

    // 当前目录和列表数据
    private File currentDirectory;
    private final List<File> audioFiles = new ArrayList<>();

    // 当前是否正在显示歌曲列表
    private boolean showingSongs = false;

    // 列表适配器
    private AudioAdapter adapter;

    // 播放器
    private MediaPlayer mediaPlayer;

    // 当前播放歌曲索引
    private int currentSongIndex = -1;

    // 是否已经准备好播放
    private boolean isPrepared = false;

    // 是否正在异步准备
    private boolean isPreparing = false;

    // 最近播放记录：只保存最后一次播放的歌曲路径
    private static final String PREFS_NAME = "player_history";
    private static final String KEY_LAST_PLAYED = "last_played_path";

    // 进度更新
    private final Handler handler = new Handler();

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();

            if (mediaPlayer != null && isPrepared) {
                handler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
// 指定当前 Activity 的物理音量键控制媒体音量
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        initViews();
        initEvents();
        initVolumeControl();
        initBackPressed();
        checkStoragePermission();

        restoreLastPlayed();//重启时，恢复播放
    }
    /**
     * 保存最近播放的歌曲，只保留一条记录
     */
    private void saveLastPlayed(File file) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PLAYED, file.getAbsolutePath())
                .apply();
    }

    /**
     * 读取最近播放的歌曲路径
     */
    private String getLastPlayedPath() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_LAST_PLAYED, "");
    }

    /**
     * 删除无效的播放记录
     */
    private void clearLastPlayed() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_PLAYED)
                .apply();
    }
    /**
     * 初始化页面控件
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

        adapter = new AudioAdapter();
        listAudio.setAdapter(adapter);

        seekVolume = findViewById(R.id.seekVolume);
        txtVolumeValue = findViewById(R.id.txtVolumeValue);
        btnMute = findViewById(R.id.btnMute);
        // 获取系统音量管理器
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    /**
     * 初始化按钮事件
     */
    private void initEvents() {

        btnBack.setOnClickListener(v -> goBack());

        btnPlay.setOnClickListener(v -> togglePlayback());

        btnPrevious.setOnClickListener(v -> playPrevious());

        btnNext.setOnClickListener(v -> playNext());

        // 拖动播放进度
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(
                    SeekBar seekBar,
                    int progress,
                    boolean fromUser) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

                if (mediaPlayer != null && isPrepared) {

                    int duration = mediaPlayer.getDuration();

                    int position = (int) (
                            duration * (seekBar.getProgress() / 1000.0)
                    );

                    mediaPlayer.seekTo(position);
                    updateProgress();
                }
            }
        });

        // 点击列表项目
        listAudio.setOnItemClickListener((parent, view, position, id) -> {

            if (!showingSongs) {

                File directory = audioFiles.get(position);
                loadMp3Files(directory);

            } else {

                playFile(position);
            }
        });
    }

    /**
     * 检查存储读取权限
     */
    private void checkStoragePermission() {

        // Android 5.x 无需运行时权限
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

    /**
     * 权限申请结果
     */
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
                Toast.makeText(this, "请允许读取存储权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 显示音乐根目录下的文件夹
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

        // 启动时尝试恢复上次播放的歌曲
        //restoreLastPlayed();
    }

    /**
     * 显示指定文件夹中的 MP3 文件
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
// 根据保存的路径查找最近播放歌曲
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

                // 恢复歌曲名称及继续播放按钮
                File lastFile = audioFiles.get(currentSongIndex);

                txtPlayingName.setText(lastFile.getName());
                btnPlay.setText("继续播放");

                txtStatus.setText("已恢复上次播放记录");

                // 自动滚动到最近播放行
                listAudio.setSelection(currentSongIndex);
            }
        }
    }

    /**
     * 点击歌曲后播放
     */
    private void playFile(int index) {

        if (index < 0 || index >= audioFiles.size()) {
            return;
        }

        File file = audioFiles.get(index);

        currentSongIndex = index;

// 保存最近播放歌曲，覆盖之前的记录
        saveLastPlayed(file);

        releasePlayer();

        txtPlayingName.setText(file.getName());
        txtCurrentTime.setText("00:00");
        txtTotalTime.setText("00:00");

        seekBar.setProgress(0);
        btnPlay.setText("准备中");

        txtStatus.setText("正在加载：" + file.getName());

        try {

            mediaPlayer = new MediaPlayer();
// 明确使用系统媒体音量通道
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

            mediaPlayer.setDataSource(file.getAbsolutePath());

            isPrepared = false;
            isPreparing = true;

            mediaPlayer.setOnPreparedListener(mp -> {

                isPrepared = true;
                isPreparing = false;

                mp.start();

                btnPlay.setText("暂停");

                txtStatus.setText("正在播放");

                txtTotalTime.setText(formatTime(mp.getDuration()));

                updateProgress();

                handler.removeCallbacks(progressRunnable);
                handler.post(progressRunnable);
            });

            mediaPlayer.setOnCompletionListener(mp -> {

                if (currentSongIndex < audioFiles.size() - 1) {

                    playNext();

                } else {

                    releasePlayer();

                    btnPlay.setText("播放");
                    txtStatus.setText("播放列表已结束");

                    seekBar.setProgress(1000);
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {

                releasePlayer();

                btnPlay.setText("播放");
                txtStatus.setText("播放失败，请检查音频文件");

                Toast.makeText(
                        this,
                        "无法播放该 MP3 文件",
                        Toast.LENGTH_SHORT
                ).show();

                return true;
            });

            mediaPlayer.prepareAsync();

        } catch (Exception e) {

            releasePlayer();

            btnPlay.setText("播放");
            txtStatus.setText("打开歌曲失败");

            Toast.makeText(
                    this,
                    "播放失败：" + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }

        adapter.notifyDataSetChanged();
    }

    /**
     * 播放 / 暂停
     */
    private void togglePlayback() {

        if (mediaPlayer == null) {

            if (showingSongs && !audioFiles.isEmpty()) {
                playFile(currentSongIndex >= 0 ? currentSongIndex : 0);
            } else {
                Toast.makeText(this, "请先进入歌曲文件夹", Toast.LENGTH_SHORT).show();
            }

            return;
        }

        if (isPreparing) {
            return;
        }

        if (!isPrepared) {
            return;
        }

        if (mediaPlayer.isPlaying()) {

            mediaPlayer.pause();
            btnPlay.setText("继续");
            txtStatus.setText("已暂停");

        } else {

            mediaPlayer.start();
            btnPlay.setText("暂停");
            txtStatus.setText("正在播放");

            handler.removeCallbacks(progressRunnable);
            handler.post(progressRunnable);
        }
    }

    /**
     * 上一首
     */
    private void playPrevious() {

        if (!showingSongs || audioFiles.isEmpty()) {
            return;
        }

        if (currentSongIndex <= 0) {
            playFile(0);
        } else {
            playFile(currentSongIndex - 1);
        }
    }

    /**
     * 下一首
     * 最后一首不循环
     */
    private void playNext() {

        if (!showingSongs || audioFiles.isEmpty()) {
            return;
        }

        if (currentSongIndex >= audioFiles.size() - 1) {

            releasePlayer();

            btnPlay.setText("播放");
            txtStatus.setText("已经是最后一首");

            return;
        }

        playFile(currentSongIndex + 1);
    }

    /**
     * 更新播放进度
     */
    private void updateProgress() {

        if (mediaPlayer == null || !isPrepared) {
            return;
        }

        try {

            int duration = mediaPlayer.getDuration();
            int position = mediaPlayer.getCurrentPosition();

            if (duration > 0) {

                int progress = (int) (
                        position * 1000.0 / duration
                );

                seekBar.setProgress(progress);
            }

            txtCurrentTime.setText(formatTime(position));

            txtTotalTime.setText(formatTime(duration));

        } catch (Exception ignored) {
        }
    }

    /**
     * 格式化时间
     */
    private String formatTime(int milliseconds) {

        int totalSeconds = milliseconds / 1000;

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
     * 释放播放器
     */
    private void releasePlayer() {

        handler.removeCallbacks(progressRunnable);

        if (mediaPlayer != null) {

            try {
                mediaPlayer.setOnCompletionListener(null);
                mediaPlayer.setOnPreparedListener(null);
                mediaPlayer.setOnErrorListener(null);
                mediaPlayer.release();
            } catch (Exception ignored) {
            }

            mediaPlayer = null;
        }

        isPrepared = false;
        isPreparing = false;
    }

    /**
     * 返回文件夹列表
     */
    private void goBack() {

        if (showingSongs) {
            showDirectories();
        }
    }
    /**
     * 恢复最近一次播放的歌曲
     *
     * 如果歌曲仍然存在：
     * 1. 自动进入歌曲所在文件夹
     * 2. 定位到上次播放的歌曲
     * 3. 高亮显示
     * 4. 等待用户点击继续播放
     */
    private void restoreLastPlayed() {

        String path = getLastPlayedPath();

        if (path == null || path.trim().isEmpty()) {
            return;
        }

        File lastFile = new File(path);

        // 歌曲已经被删除或移动
        if (!lastFile.exists() || !lastFile.isFile()) {
            clearLastPlayed();
            return;
        }

        File parent = lastFile.getParentFile();

        if (parent == null || !parent.isDirectory()) {
            clearLastPlayed();
            return;
        }

        // 只允许恢复音乐根目录下一级文件夹中的歌曲
        File root = new File(MUSIC_ROOT);

        if (parent.getParentFile() == null ||
                !parent.getParentFile().getAbsolutePath()
                        .equals(root.getAbsolutePath())) {
            clearLastPlayed();
            return;
        }

        // 自动打开上次播放歌曲所在的文件夹
        loadMp3Files(parent);
    }
    /**
     * 自然排序
     * 例如：1.mp3、2.mp3、10.mp3
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

                result = new BigInteger(part1).compareTo(
                        new BigInteger(part2));

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
     * 格式化文件大小
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
     * 列表适配器
     * 使用 item_audio.xml 创建每一行
     */
    private class AudioAdapter extends BaseAdapter {

        private final LayoutInflater inflater =
                LayoutInflater.from(MainActivity.this);

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
        public View getView(int position, View convertView, ViewGroup parent) {

            ViewHolder holder;

            if (convertView == null) {

                convertView = inflater.inflate(
                        R.layout.item_audio,
                        parent,
                        false
                );

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

                // 文件夹行恢复白色背景
                convertView.setBackgroundColor(0xFFFFFFFF);

            } else {

                holder.icon.setText("♫");
                holder.arrow.setVisibility(View.GONE);

                holder.info.setText(formatFileSize(file.length()));

                // 判断是否为最近一次播放的歌曲
                boolean isLastPlayed =
                        file.getAbsolutePath().equals(getLastPlayedPath());

                if (isLastPlayed) {

                    // 最近播放行：浅蓝背景、蓝色图标和文字
                    convertView.setBackgroundColor(0xFFE6F7FC);

                    holder.icon.setTextColor(0xFF159BCB);
                    holder.name.setTextColor(0xFF159BCB);
                    holder.info.setTextColor(0xFF159BCB);

                } else {

                    // 普通歌曲行
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

    /**
     * 注册系统返回事件
     * 使用 AndroidX 兼容不同 Android 版本
     */
    private void initBackPressed() {

        getOnBackPressedDispatcher().addCallback(
                this,
                new androidx.activity.OnBackPressedCallback(true) {

                    @Override
                    public void handleOnBackPressed() {

                        if (showingSongs) {

                            // 当前在歌曲列表，返回文件夹列表
                            goBack();

                        } else {

                            // 当前在根目录，允许系统执行默认返回
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                }
        );
    }

    /**
     * 页面销毁时释放音频资源
     */
    @Override
    protected void onDestroy() {

        releasePlayer();

        super.onDestroy();
    }

    /**
     * 初始化全局媒体音量控制
     * 音量滑块与手机物理音量键使用同一个系统音量
     */
    private void initVolumeControl() {

        if (audioManager == null) {
            return;
        }

        int maxVolume = audioManager.getStreamMaxVolume(
                AudioManager.STREAM_MUSIC);

        int currentVolume = audioManager.getStreamVolume(
                AudioManager.STREAM_MUSIC);

        seekVolume.setMax(maxVolume);
        seekVolume.setProgress(currentVolume);

        updateVolumeText(currentVolume, maxVolume);

        // 拖动滑块调整系统媒体音量
        seekVolume.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {

                        if (fromUser) {
                            audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    progress,
                                    0);

                            updateVolumeText(progress, maxVolume);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });

        // 点击喇叭图标切换静音
        btnMute.setOnClickListener(v -> {

            int volume = audioManager.getStreamVolume(
                    AudioManager.STREAM_MUSIC);

            if (volume > 0) {

                previousVolume = volume;

                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC, 0, 0);

            } else {

                int restoreVolume = previousVolume > 0
                        ? previousVolume
                        : Math.max(1, maxVolume / 2);

                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        restoreVolume,
                        0);
            }

            refreshVolumeControl();
        });
    }

    /**
     * 更新音量百分比和图标
     */
    private void updateVolumeText(int volume, int maxVolume) {

        int percent = maxVolume > 0
                ? volume * 100 / maxVolume
                : 0;

        txtVolumeValue.setText(percent + "%");

        if (volume == 0) {
            btnMute.setText("🔇");
        } else if (percent < 40) {
            btnMute.setText("🔈");
        } else {
            btnMute.setText("🔊");
        }
    }

    /**
     * 从系统重新读取媒体音量并更新滑块
     */
    private void refreshVolumeControl() {

        if (audioManager == null || seekVolume == null) {
            return;
        }

        int maxVolume = audioManager.getStreamMaxVolume(
                AudioManager.STREAM_MUSIC);

        int currentVolume = audioManager.getStreamVolume(
                AudioManager.STREAM_MUSIC);

        seekVolume.setProgress(currentVolume);

        updateVolumeText(currentVolume, maxVolume);
    }

    /**
     * 手机物理音量键松开后，同步 App 内音量滑块
     *
     * 实际音量调整由系统处理，不拦截物理按键
     */
    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {

        boolean result = super.onKeyUp(keyCode, event);

        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {

            handler.postDelayed(this::refreshVolumeControl, 100);
        }

        return result;
    }
}
