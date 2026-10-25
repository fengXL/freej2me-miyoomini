/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
*/
package org.recompile.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * 音频工具：格式嗅探、缓存文件、正弦WAV合成。
 * 供 PlatformPlayer / com.nokia.mid.sound.Sound / Manager.playTone 共用。
 */
public class AudioUtil
{
	// 嗅探出的音频格式
	public static final int FMT_NONE = 0;
	public static final int FMT_MIDI = 1;
	public static final int FMT_WAV  = 2;
	public static final int FMT_MP3  = 3;
	public static final int FMT_OGG  = 4;
	public static final int FMT_AMR  = 5;

	/**
	 * 按文件头魔数判断音频格式（MIME 类型只作参考，不作为唯一依据）
	 */
	public static int sniffFormat(byte[] d)
	{
		if (d == null || d.length < 4) return FMT_NONE;

		if (d[0]=='M' && d[1]=='T' && d[2]=='h' && d[3]=='d') return FMT_MIDI;

		if (d.length >= 12 && d[0]=='R' && d[1]=='I' && d[2]=='F' && d[3]=='F'
			&& d[8]=='W' && d[9]=='A' && d[10]=='V' && d[11]=='E') return FMT_WAV;

		if (d.length >= 3 && d[0]=='I' && d[1]=='D' && d[2]=='3') return FMT_MP3;

		if (d.length >= 2 && (d[0]&0xFF)==0xFF && ((d[1]&0xFF)&0xE0)==0xE0) return FMT_MP3;

		if (d[0]=='O' && d[1]=='g' && d[2]=='g' && d[3]=='S') return FMT_OGG;

		if (d.length >= 5 && d[0]=='#' && d[1]=='!' && d[2]=='A' && d[3]=='M' && d[4]=='R') return FMT_AMR;

		return FMT_NONE;
	}

	/**
	 * 全量数据的MD5（修复旧实现只取前1KB导致不同文件互相覆盖缓存的问题）
	 */
	public static String md5(byte[] data)
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] hashed = md.digest(data);
			StringBuilder sb = new StringBuilder();
			for (byte b : hashed) sb.append(String.format("%02x", b));
			return sb.toString();
		}
		catch (Exception e)
		{
			return String.valueOf(java.util.Arrays.hashCode(data));
		}
	}

	/**
	 * 把音频数据写入缓存文件（已存在则复用），返回缓存路径。
	 * subdir 通常是 "./rms/<游戏名>"
	 */
	public static String writeCacheFile(String subdir, byte[] data, String ext)
	{
		try
		{
			Files.createDirectories(Paths.get(subdir));
			String path = subdir + "/" + md5(data) + ext;
			File file = new File(path);
			if (!file.exists())
			{
				file.createNewFile();
				FileOutputStream fos = new FileOutputStream(file);
				fos.write(data);
				fos.close();
			}
			return path;
		}
		catch (Exception e)
		{
			System.out.println("写入音频缓存失败: " + e.getMessage());
			return null;
		}
	}

	/**
	 * 合成单频正弦WAV（16bit 单声道 44100Hz），用于 Nokia Sound 蜂鸣和 playTone。
	 * @param freq 频率 Hz
	 * @param durationMs 时长 毫秒
	 * @param volume 音量 0-100
	 */
	public static byte[] synthesizeWav(double freq, int durationMs, int volume)
	{
		if (durationMs < 10) durationMs = 10;
		if (volume < 0) volume = 0;
		if (volume > 100) volume = 100;

		final int rate = 44100;
		final int samples = (int)((long)rate * durationMs / 1000);
		final double amp = 32000.0 * volume / 100.0;
		final int dataLen = samples * 2;
		final int totalLen = 44 + dataLen;

		byte[] wav = new byte[totalLen];
		// RIFF头
		wav[0]='R'; wav[1]='I'; wav[2]='F'; wav[3]='F';
		writeIntLE(wav, 4, totalLen - 8);
		wav[8]='W'; wav[9]='A'; wav[10]='V'; wav[11]='E';
		wav[12]='f'; wav[13]='m'; wav[14]='t'; wav[15]=' ';
		writeIntLE(wav, 16, 16);        // fmt块大小
		writeShortLE(wav, 20, (short)1);// PCM
		writeShortLE(wav, 22, (short)1);// 单声道
		writeIntLE(wav, 24, rate);
		writeIntLE(wav, 28, rate * 2);
		writeShortLE(wav, 32, (short)2);
		writeShortLE(wav, 34, (short)16);
		wav[36]='d'; wav[37]='a'; wav[38]='t'; wav[39]='a';
		writeIntLE(wav, 40, dataLen);

		for (int i = 0; i < samples; i++)
		{
			// 10ms 淡入淡出，避免爆音
			double fade = 1.0;
			int fadeSamples = rate / 100;
			if (i < fadeSamples) fade = (double)i / fadeSamples;
			else if (i > samples - fadeSamples) fade = (double)(samples - i) / fadeSamples;

			double v = Math.sin(2.0 * Math.PI * freq * i / rate) * amp * fade;
			short s = (short) v;
			wav[44 + i*2] = (byte)(s & 0xFF);
			wav[44 + i*2 + 1] = (byte)((s >> 8) & 0xFF);
		}
		return wav;
	}

	/**
	 * MIDI音符号转频率
	 */
	public static double noteToFreq(int note)
	{
		return 440.0 * Math.pow(2.0, (note - 69) / 12.0);
	}

	private static void writeIntLE(byte[] b, int off, int v)
	{
		b[off]   = (byte)(v & 0xFF);
		b[off+1] = (byte)((v >> 8) & 0xFF);
		b[off+2] = (byte)((v >> 16) & 0xFF);
		b[off+3] = (byte)((v >> 24) & 0xFF);
	}

	private static void writeShortLE(byte[] b, int off, short v)
	{
		b[off]   = (byte)(v & 0xFF);
		b[off+1] = (byte)((v >> 8) & 0xFF);
	}
}
