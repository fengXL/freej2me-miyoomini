/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	FreeJ2ME is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with FreeJ2ME.  If not, see http://www.gnu.org/licenses/
*/
package org.recompile.mobile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import java.util.Vector;


import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

import javax.microedition.media.Control;
import javax.microedition.media.Controllable;

import org.recompile.mobile.SdlMixerManager;


/**
所谓的stop的使用,其实是返回prefetched(缓冲读取)的状态,因此并没有所谓的stopped的状态.一般如果播放完毕后会自动回到prefetched(缓冲读取)的状态
unrealized(没有实现)----->realized(实现)----->prefetched(缓冲读取)----->started(播放状态)
          realize()            prefetch()           start()

started(播放状态)----->prefetched(缓冲读取)            realized(实现)----->unrealized(没有实现)
          stop()                                     deallocate()释放资源

以上4个状态随时都可以用close()返回到closed状态

音频路由：
  WAV  -> chunk通道（每个Player独占一个通道，多个音效可同时播放、互不打断）
  MIDI/MP3/OGG -> music通道（全局单流，符合游戏切换BGM的行为）
  格式按文件头魔数嗅探决定，MIME 类型只作参考。

**/
public class PlatformPlayer implements Player
{

	private String contentType = "";

	private audioplayer player;

	private int state = Player.UNREALIZED;

	private Vector<PlayerListener> listeners;

	private Control[] controls;


	public PlatformPlayer(InputStream stream, String type)
	{
		listeners = new Vector<PlayerListener>();
		controls = new Control[3];

		contentType = type;

		if(Mobile.sound == false)
		{
			player = new audioplayer();
		}
		else
		{
			// 完整读入音频数据
			byte[] data = readAll(stream);

			int fmt = AudioUtil.sniffFormat(data);

			// 嗅探不出来时参考MIME类型
			if(fmt == AudioUtil.FMT_NONE && type != null)
			{
				String t = type.toLowerCase();
				if(t.endsWith("midi") || t.endsWith("mid") || t.endsWith("sp-midi") || t.endsWith("spmidi"))
				{
					fmt = AudioUtil.FMT_MIDI;
				}
			}

			switch (fmt)
			{
				case AudioUtil.FMT_WAV:
					player = new sdlWavPlayer(data);
					break;

				case AudioUtil.FMT_MIDI:
					player = new sdlPlayer(data, ".mid");
					break;

				case AudioUtil.FMT_MP3:
					player = new sdlPlayer(data, ".mp3");
					break;

				case AudioUtil.FMT_OGG:
					player = new sdlPlayer(data, ".ogg");
					break;

				case AudioUtil.FMT_AMR:
					System.out.println("不支持的音频格式: AMR (" + type + ")");
					player = new audioplayer();
					break;

				default:
					// 未知格式：尝试用music通道加载（Mix_LoadMUS 会自行嗅探内容）
					System.out.println("未知音频格式(type=" + type + "), 尝试按流式音频加载");
					player = new sdlPlayer(data, ".aud");
					break;
			}
		}
		controls[0] = new volumeControl(player);
		controls[1] = new tempoControl();
		controls[2] = new midiControl(player);

		System.out.println("media type: "+type);
	}

	public PlatformPlayer(String locator)
	{
		player = new audioplayer();
		listeners = new Vector<PlayerListener>();
		controls = new Control[3];
		System.out.println("Player locator: "+locator);
	}

	private static byte[] readAll(InputStream stream)
	{
		try
		{
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int len;
			while ((len = stream.read(chunk)) != -1)
			{
				buffer.write(chunk, 0, len);
			}
			return buffer.toByteArray();
		}
		catch (Exception e)
		{
			System.out.println("读取音频数据失败: " + e.getMessage());
			return new byte[0];
		}
	}

	public void close()
	{
		try
		{
			player.stop();
			player.deallocate(); // 游戏换声音库时只调close：必须在此释放chunk/music并归还音效通道，否则32个通道会被逐渐漏光
			state = Player.CLOSED;
			notifyListeners(PlayerListener.CLOSED, null);
		}
		catch (Exception e) { }
		state = Player.CLOSED;
	}

	public int getState()
	{
		return state;
	}

	public void start()
	{
		if(state == Player.CLOSED) { return; } // 已close的Player不能再发声
		try
		{
			player.start();
		}
		catch (Exception e) {  }
	}

	public void stop()
	{
		if(state == Player.CLOSED) { return; }
		try
		{
			player.stop();

		}
		catch (Exception e) { }
	}

	public void addPlayerListener(PlayerListener playerListener)
	{
		listeners.add(playerListener);
	}

	public void removePlayerListener(PlayerListener playerListener)
	{
		listeners.remove(playerListener);
	}

	private void notifyListeners(String event, Object eventData)
	{
		for(int i=0; i<listeners.size(); i++)
		{
			listeners.get(i).playerUpdate(this, event, eventData);
		}
	}

	public void deallocate()
	{
		stop();
		player.deallocate();

		state = Player.REALIZED;
	}

	public String getContentType() { return contentType; }

	public long getDuration() { return Player.TIME_UNKNOWN; }

	public long getMediaTime() { return player.getMediaTime(); }

	public void prefetch() { state = Player.PREFETCHED; }

	public void realize() { state = Player.REALIZED; }

	public void setLoopCount(int count) { player.setLoopCount(count); }

	public long setMediaTime(long now) { return player.setMediaTime(now); }

	// Controllable interface //

	public Control getControl(String controlType)
	{
		if(controlType.equals("VolumeControl")) { return controls[0]; }
		if(controlType.equals("TempoControl")) { return controls[1]; }
		if(controlType.equals("MIDIControl")) { return controls[2]; }
		if(controlType.equals("javax.microedition.media.control.VolumeControl")) { return controls[0]; }
		if(controlType.equals("javax.microedition.media.control.TempoControl")) { return controls[1]; }
		if(controlType.equals("javax.microedition.media.control.MIDIControl")) { return controls[2]; }
		return null;
	}

	public Control[] getControls()
	{
		return controls;
	}

	// Players //

	public static class audioplayer
	{
		public void start() {  }
		public void stop() {  }
		public void setLoopCount(int count) {  }
		public long setMediaTime(long now) { return now; }
		public long getMediaTime() { return 0; }
		public boolean isRunning() { return false; }
		public void deallocate() {  }
		public void setVolume(int vol) { };
	}

	/**
	 * music通道播放器（MIDI/MP3/OGG等流式音频，全局单流）
	 */
	public class sdlPlayer extends audioplayer
	{
		private int loops = 0;

		private String bgmFileName="";

		private SdlMixerManager manager;

		private long currentMusicHandle = 0;

		private boolean isOpen = false;

		private boolean seekToStart = false; // 游戏setMediaTime(0)后要求下次start从头播

		public sdlPlayer(byte[] data, String ext)
		{
			try
			{
				if (data.length == 0) { return; }

				String rmsPath = "./rms/"+Mobile.getPlatform().loader.suitename;
				bgmFileName = AudioUtil.writeCacheFile(rmsPath, data, ext);

				SdlMixerManager.init();

				manager = new SdlMixerManager();
				currentMusicHandle = SdlMixerManager.acquireMusic(bgmFileName, this);
				if (currentMusicHandle == -1) {
					SdlMixerManager.audioLog("music加载失败 " + bgmFileName);
					currentMusicHandle = 0;
				} else if (currentMusicHandle != 0) {
					registerMusicPlayer(this);
				}

			}
			catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}

		public void start()
		{
			if(manager == null) { return; }

			if(currentMusicHandle==0)
			{
				currentMusicHandle = SdlMixerManager.acquireMusic(bgmFileName, this);
				if (currentMusicHandle > 0) {
					registerMusicPlayer(this);
				}
			}

			if (currentMusicHandle == -1 || currentMusicHandle == 0) {
				SdlMixerManager.audioLog("music播放失败(未加载) " + bgmFileName);
				return;
			}

			// 要求从头播时跳过恢复（PlayMusic总是从0开始）；否则先尝试续播暂停中的流
			if(!seekToStart)
			{
				manager.sdlMixerResumeMusic(currentMusicHandle);
			}

			if(seekToStart || !isOpen || !manager.sdlMixerIsPlaying(currentMusicHandle))
			{
				int result = manager.sdlMixerPlayMusic(currentMusicHandle, loops);
				if (result == 0) {
					isOpen = true;
					seekToStart = false;
					SdlMixerManager.audioLog("music播放 " + bgmFileName + " loops=" + loops);
				} else {
					SdlMixerManager.audioLog("music播放失败 " + bgmFileName);
					return;
				}
			}

			state = Player.STARTED;
			notifyListeners(PlayerListener.STARTED, 0);
		}

		public void stop()
		{
			if(state == Player.PREFETCHED) { return; }
			if(manager != null && currentMusicHandle != 0)
			{
				manager.sdlMixerPauseMusic(currentMusicHandle);
			}
			state = Player.PREFETCHED;
			notifyListeners(PlayerListener.STOPPED, 0);
			// 单music流：本曲暂停后流就静了。真机上此时别的仍在STARTED的Player还在响
			// （例如被本曲顶掉的BGM），代为恢复，避免"事件音乐暂停后BGM永久消失"
			resumeStackedMusic(this);
		}

		public void deallocate()
		{
			unregisterMusicPlayer(this);
			if (currentMusicHandle != 0) {
				SdlMixerManager.releaseMusic(bgmFileName, currentMusicHandle);
				currentMusicHandle = 0;
			}

			isOpen = false;
		}

		public void setLoopCount(int count)
		{
			loops = count;
			System.out.println("media loop: " + loops);
		}

		public void setVolume(int vol)
		{
			if(manager != null)
			{
				manager.sdlMixerSetVolume(vol);
			}
		}

		public long setMediaTime(long now)
		{
			// music通道无法任意seek：只记录"从头播"请求（游戏每次播放前都会setMediaTime(0)）
			seekToStart = (now <= 0);
			return now;
		}

		public void onPlaybackComplete()
		{
			SdlMixerManager.audioLog("music自然播完 " + bgmFileName + " loops=" + loops);

			// 单music流：若本曲曾把别的BGM顶掉、而游戏并未显式stop它（真机多Player可叠播，
			// 游戏以为BGM还在响），先代为恢复，再通知本曲END_OF_MEDIA（游戏监听器重播可正常接管）
			boolean resumedOther = resumeStackedMusic(this);

			if(state == Player.STARTED) { state = Player.PREFETCHED; }
			notifyListeners(PlayerListener.END_OF_MEDIA, 0);

			// 无限循环的BGM不应自然结束；若后端悄悄终止了流（没有任何结束回调），
			// 由native看门狗补发本事件，这里代游戏重启它
			if(!resumedOther && loops == -1 && state != Player.CLOSED)
			{
				SdlMixerManager.audioLog("看门狗: 重启被后端终止的循环BGM " + bgmFileName);
				start();
			}
		}

		// 供注册表判断：游戏视角里这个播放器是否还在STARTED（没有被stop/deallocate过）
		private boolean isStarted()
		{
			return state == Player.STARTED;
		}
	}

	// music播放器注册表：配合单music流，在当前曲目自然播完时找回被顶掉的BGM
	private static final java.util.List<sdlPlayer> activeMusic = new java.util.ArrayList<sdlPlayer>();

	private static void registerMusicPlayer(sdlPlayer p)
	{
		if (p == null) { return; }
		synchronized (activeMusic) {
			if (!activeMusic.contains(p)) { activeMusic.add(p); }
		}
	}

	private static void unregisterMusicPlayer(sdlPlayer p)
	{
		if (p == null) { return; }
		synchronized (activeMusic) {
			activeMusic.remove(p);
		}
	}

	// 找一个仍处于STARTED（游戏没stop/deallocate过）且当前不在发声的music播放器并重启它。
	// 返回是否恢复了某个播放器。
	private static boolean resumeStackedMusic(sdlPlayer finished)
	{
		sdlPlayer candidate = null;
		synchronized (activeMusic) {
			for (int i = activeMusic.size() - 1; i >= 0; i--) {
				sdlPlayer p = activeMusic.get(i);
				if (p == finished) { continue; }
				if (p.manager == null || p.currentMusicHandle == 0) { continue; }
				if (!p.isStarted()) { continue; }
				// 已在当前流上发声的不动它（例如本曲stop时顶上的正是它）
				if (p.manager.sdlMixerIsPlaying(p.currentMusicHandle)) { continue; }
				candidate = p;
				break;
			}
		}
		if (candidate != null) {
			SdlMixerManager.audioLog("恢复被顶掉的BGM " + candidate.bgmFileName);
			candidate.start();
			return true;
		}
		return false;
	}


	/**
	 * 音效播放器（chunk通道，多路并发、互不打断）
	 */
	public class sdlWavPlayer extends audioplayer
	{
		private int loops = 0;

		private String bgmFileName="";

		private SdlMixerManager manager;

		private long currentMusicHandle = 0;

		private boolean isOpen = false;

		public sdlWavPlayer(byte[] data)
		{
			try
			{
				if (data.length == 0) { return; }

				String rmsPath = "./rms/"+Mobile.getPlatform().loader.suitename;
				bgmFileName = AudioUtil.writeCacheFile(rmsPath, data, ".wav");

				SdlMixerManager.init();

				manager = new SdlMixerManager();
				currentMusicHandle = SdlMixerManager.acquireWav(bgmFileName);
				if (currentMusicHandle == -1) {
					SdlMixerManager.audioLog("sfx加载失败(通道耗尽?) " + bgmFileName);
					currentMusicHandle = 0;
				}

			}
			catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}

		public void start()
		{
			if(manager == null) { return; }

			if(currentMusicHandle==0)
			{
				currentMusicHandle = SdlMixerManager.acquireWav(bgmFileName);
				if (currentMusicHandle == -1) {
					SdlMixerManager.audioLog("sfx加载失败(通道耗尽?) " + bgmFileName);
					currentMusicHandle = 0;
				}
			}

			if (currentMusicHandle == -1 || currentMusicHandle == 0) {
				return;
			}

			// 总是从头播放（Mix_PlayChannel会清除paused标志）。
			// 不能用"IsPlaying则跳过"：已暂停的通道Mix_Playing也返回1，
			// 会导致stop(暂停)后再start的音效永久无声（游戏的暂停/恢复命令正是这个序列）
			int result = manager.sdlMixerPlayWav(currentMusicHandle, loops);
			if (result == 0) {
				isOpen = true;
			} else {
				SdlMixerManager.audioLog("sfx播放失败 " + bgmFileName);
				return;
			}

			state = Player.STARTED;
			notifyListeners(PlayerListener.STARTED, 0);
		}

		public void stop()
		{
			if(state == Player.PREFETCHED) { return; }
			if(manager != null && currentMusicHandle != 0)
			{
				manager.sdlMixerPauseWav(currentMusicHandle);
			}
			state = Player.PREFETCHED;
			notifyListeners(PlayerListener.STOPPED, 0);
		}

		public void deallocate()
		{
			if (currentMusicHandle != 0) {
				SdlMixerManager.releaseWav(bgmFileName, currentMusicHandle);
				currentMusicHandle = 0;
			}

			isOpen = false;
		}

		public void setLoopCount(int count)
		{
			if(count>0)
			{
				count=count-1;
			}
			loops = count;
			System.out.println("sfx loop: " + loops);
		}

		public void setVolume(int vol)
		{
			if(manager != null && currentMusicHandle != 0)
			{
				manager.sdlMixerSetVolumeWav(currentMusicHandle, vol);
			}
		}

	}

	// Controls //

	private class midiControl implements javax.microedition.media.control.MIDIControl
	{
		private audioplayer manager;
		public midiControl(audioplayer m)
		{
			manager = m;
		}

		public int[] getBankList(boolean custom) { return new int[]{}; }

		public int getChannelVolume(int channel) { return 0; }

		public java.lang.String getKeyName(int bank, int prog, int key) { return ""; }

		public int[] getProgram(int channel) { return new int[]{}; }

		public int[] getProgramList(int bank) { return new int[]{}; }

		public java.lang.String getProgramName(int bank, int prog) { return ""; }

		public boolean isBankQuerySupported() { return false; }

		public int longMidiEvent(byte[] data, int offset, int length) { return 0; }

		public void setChannelVolume(int channel, int volume) { manager.setVolume(volume); }

		public void setProgram(int channel, int bank, int program) {  }

		public void shortMidiEvent(int type, int data1, int data2) {  }
	}

	private class volumeControl implements javax.microedition.media.control.VolumeControl
	{
		private audioplayer manager;
		public volumeControl(audioplayer m)
		{
			manager = m;
		}
		private int level = 100;
		private boolean muted = false;

		public int getLevel() { return level; }

		public boolean isMuted() { return muted; }

		public int setLevel(int value) { level = value; manager.setVolume(value); return level; }

		public void setMute(boolean mute) { muted = mute; }//静音
	}

	private class tempoControl implements javax.microedition.media.control.TempoControl//设置midi节拍
	{
		int tempo = 5000;
		int rate = 5000;

		public int getTempo() { return tempo; }

		public int setTempo(int millitempo) { tempo = millitempo; return tempo; }

		// RateControl interface
		public int getMaxRate() { return rate; }

		public int getMinRate() { return rate; }

		public int getRate() { return rate; }

		public int setRate(int millirate) { rate=millirate; return rate; }
	}

}
