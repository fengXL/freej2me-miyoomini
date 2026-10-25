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
package com.nokia.mid.sound;

import org.recompile.mobile.AudioUtil;
import org.recompile.mobile.Mobile;
import org.recompile.mobile.SdlMixerManager;

/**
 * Nokia UI Sound API 实现。
 *
 * FORMAT_WAV 的数据走 chunk 通道播放：每个 Sound 实例独占一个通道，
 * 多个音效可以同时播放、互不打断（这也是很多游戏音效"不完整"的主因——
 * 旧实现是空壳，完全没有声音）。
 *
 * Sound(freq, duration) 蜂鸣用 Java 合成的正弦 WAV 播放。
 * FORMAT_TONE（诺基亚二进制铃声）暂不支持。
 */
public class Sound
{
	public static final int FORMAT_TONE = 1;
	public static final int FORMAT_WAV = 5;
	public static final int SOUND_PLAYING = 0;
	public static final int SOUND_STOPPED = 1;
	public static final int SOUND_UNINITIALIZED = 3;
	public static final int SOUND_SOUND_STOPPED_EVENT = 0;


	private byte[] wavData = null;
	private int fmt = FORMAT_TONE;

	private SdlMixerManager manager = null;
	private long handle = 0;

	private int gain = 255;

	private int state = SOUND_UNINITIALIZED;

	private SoundListener listener = null;

	private int loopRemaining = 0;


	public Sound(byte[] data, int type) { init(data, type); }

	public Sound(int freq, long duration) { init(freq, duration); }


	public static int getConcurrentSoundCount(int type) { return 8; }

	public int getGain() { return gain; }

	public int getState()
	{
		// 正在播放时上报 PLAYING
		if (state == SOUND_PLAYING && manager != null && handle != 0)
		{
			if (!manager.sdlMixerIsPlayingWav(handle))
			{
				state = SOUND_STOPPED;
				notifyStopped();
			}
		}
		return state;
	}

	public static int[] getSupportedFormats() { return new int[]{FORMAT_TONE, FORMAT_WAV}; }

	public void init(byte[] data, int type)
	{
		fmt = type;
		releaseHandle();

		if (type == FORMAT_WAV && data != null && data.length > 0)
		{
			// 只接受真正的WAV数据，其它格式忽略
			if (AudioUtil.sniffFormat(data) == AudioUtil.FMT_WAV)
			{
				wavData = data;
				state = SOUND_STOPPED;
			}
			else
			{
				System.out.println("Nokia Sound: 非WAV数据(type=" + type + "), 忽略");
				wavData = null;
				state = SOUND_UNINITIALIZED;
			}
		}
		else if (type == FORMAT_TONE)
		{
			// 诺基亚二进制铃声格式暂不支持
			wavData = null;
			state = SOUND_UNINITIALIZED;
		}
	}

	public void init(int freq, long duration)
	{
		fmt = FORMAT_WAV;
		releaseHandle();

		if (freq <= 0) { freq = 440; }
		if (duration <= 0) { duration = 100; }
		if (duration > 5000) { duration = 5000; }

		wavData = AudioUtil.synthesizeWav(freq, (int)duration, 100);
		state = SOUND_STOPPED;
	}

	public void play(int loop)
	{
		if (wavData == null) { return; }

		try
		{
			SdlMixerManager.init();
			if (manager == null) { manager = new SdlMixerManager(); }

			if (handle == 0)
			{
				String rmsPath = "./rms/" + Mobile.getPlatform().loader.suitename;
				String path = AudioUtil.writeCacheFile(rmsPath, wavData, ".wav");
				handle = manager.sdlMixerLoadWav(path);
			}
			if (handle == -1 || handle == 0) { return; }

			// loop: 0=播放一次, 负数=无限循环, 正数=循环指定次数
			int loops;
			if (loop < 0) { loops = -1; }
			else { loops = loop; }

			manager.sdlMixerSetVolumeWav(handle, gain * 100 / 255);
			manager.sdlMixerPlayWav(handle, loops);

			loopRemaining = loops;
			state = SOUND_PLAYING;
		}
		catch (Throwable e)
		{
			System.out.println("Nokia Sound play: " + e.getMessage());
		}
	}

	public void release()
	{
		releaseHandle();
		wavData = null;
		state = SOUND_UNINITIALIZED;
		listener = null;
	}

	public void resume()
	{
		if (manager != null && handle != 0 && state == SOUND_STOPPED)
		{
			manager.sdlMixerResumeWav(handle);
			state = SOUND_PLAYING;
		}
	}

	public void setGain(int g)
	{
		if (g < 0) g = 0;
		if (g > 255) g = 255;
		gain = g;
		if (manager != null && handle != 0)
		{
			manager.sdlMixerSetVolumeWav(handle, gain * 100 / 255);
		}
	}

	public void setSoundListener(SoundListener l) { listener = l; }

	public void stop()
	{
		if (manager != null && handle != 0)
		{
			manager.sdlMixerStopWav(handle);
		}
		if (state == SOUND_PLAYING)
		{
			state = SOUND_STOPPED;
			notifyStopped();
		}
	}

	private void releaseHandle()
	{
		if (manager != null && handle != 0)
		{
			manager.sdlMixerStopWav(handle);
			manager.sdlMixerFreeWav(handle);
			handle = 0;
		}
		state = SOUND_UNINITIALIZED;
	}

	private void notifyStopped()
	{
		if (listener != null)
		{
			try { listener.soundStateChanged(this, SOUND_SOUND_STOPPED_EVENT); }
			catch (Throwable t) { }
		}
	}

}
