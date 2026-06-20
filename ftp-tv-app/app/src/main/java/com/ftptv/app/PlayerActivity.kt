package com.ftptv.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var simpleCache: SimpleCache? = null
    private var retryCount = 0
    private var channelUrls: Array<String> = emptyArray()
    private var channelNames: Array<String> = emptyArray()
    private var currentIndex: Int = 0

    companion object {
        fun newIntent(ctx: Context, urls: Array<String>, names: Array<String>, index: Int): Intent {
            return Intent(ctx, PlayerActivity::class.java).apply {
                putExtra("channel_urls", urls)
                putExtra("channel_names", names)
                putExtra("current_index", index)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    @UnstableApi
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = 1
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        channelUrls = intent?.getStringArrayExtra("channel_urls") ?: run {
            finish(); return
        }
        channelNames = intent?.getStringArrayExtra("channel_names") ?: emptyArray()
        currentIndex = intent?.getIntExtra("current_index", 0) ?: 0

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().apply {
                setMaxVideoSize(1280, 720)
                setPreferredAudioLanguage("eng")
            })
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 15_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val cacheDir = File(cacheDir, "media3-cache")
        val evictor = LeastRecentlyUsedCacheEvictor(20 * 1024 * 1024)
        val cache = SimpleCache(cacheDir, evictor)
        simpleCache = cache

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(HlsMediaSource.Factory(cacheDataSourceFactory))
            .build()

        player?.setWakeMode(C.WAKE_MODE_NONE)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView.player = player
        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        playerView.contentDescription = getString(R.string.player_description)

        playChannel(currentIndex)

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retryCount = 0
                if (state == Player.STATE_ENDED) finish()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (retryCount < 1 && channelUrls.size > 1) {
                    retryCount++
                    player?.stop()
                    player?.clearMediaItems()
                    player?.setMediaItem(MediaItem.fromUri(Uri.parse(channelUrls[currentIndex])))
                    player?.prepare()
                    player?.play()
                } else if (channelUrls.size > 1) {
                    retryCount = 0
                    val next = (currentIndex + 1) % channelUrls.size
                    currentIndex = next
                    Toast.makeText(this@PlayerActivity, channelNames.getOrElse(next) { "" }, Toast.LENGTH_SHORT).show()
                    playChannel(next)
                } else {
                    Toast.makeText(this@PlayerActivity, error.message, Toast.LENGTH_LONG).show()
                }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                player?.stop()
                finish()
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && channelUrls.size > 1) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { switchChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { switchChannel(1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchChannel(direction: Int) {
        val newIndex = currentIndex + direction
        if (newIndex < 0 || newIndex >= channelUrls.size) return
        retryCount = 0
        currentIndex = newIndex
        val name = if (newIndex in channelNames.indices) channelNames[newIndex] else ""
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        playChannel(currentIndex)
    }

    private fun playChannel(index: Int) {
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
            p.setMediaItem(MediaItem.fromUri(Uri.parse(channelUrls[index])))
            p.prepare()
            p.play()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        simpleCache?.release()
        simpleCache = null
        super.onDestroy()
    }
}
