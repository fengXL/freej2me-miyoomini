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
package javax.microedition.media;

import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;

import org.recompile.mobile.PlatformPlayer;

import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;

public final class Manager
{

	public static final String TONE_DEVICE_LOCATOR = "device://tone";


	public static Player createPlayer(InputStream stream, String type) throws IOException, MediaException
	{
		//System.out.println("Create Player Stream "+type);
		return new PlatformPlayer(stream, type);
	}

	public static Player createPlayer(String locator) throws MediaException
	{
		System.out.println("Create Player "+locator);
		return new PlatformPlayer(locator);
	}
	
	public static Player createPlayer(DataSource source) throws IOException, MediaException {
		if (source == null) {
			System.out.println("null DataSource");
			throw new IllegalArgumentException();
		}
		String type = source.getContentType();
		String[] supportedTypes = getSupportedContentTypes(null);
		
		for (int i = 0; i < supportedTypes.length; i++) {
			if(type != null && supportedTypes[i].equals(type.toLowerCase()))
			{
				source.connect();
				SourceStream[] sourceStreams = source.getStreams();
				if (sourceStreams == null || sourceStreams.length == 0) {
					throw new MediaException();
				}
				SourceStream sourceStream = sourceStreams[0];
				InputStream stream = new InternalSourceStream(sourceStream);
				return new PlatformPlayer(stream, type);
			}
			
		}
		
		throw new MediaException();
		
	}
	
	public static String[] getSupportedContentTypes(String protocol)
	{
		//System.out.println("Get Supported Media Content Types");
		return new String[]{"audio/midi", "audio/x-wav", 
		"audio/amr", "audio/mpeg"};
	}
	
	public static String[] getSupportedProtocols(String content_type)
	{
		System.out.println("Get Supported Media Protocols");
		return new String[]{"device", "file", "http"};
	}
	
	// 上一个合成音的chunk句柄：每次playTone都新load一个chunk，不释放会把音效通道漏光
	private static long lastToneHandle = 0;

	public static void playTone(int note, int duration, int volume)
	{
		System.out.println("Play Tone");
		try
		{
			byte[] wav = org.recompile.mobile.AudioUtil.synthesizeWav(
					org.recompile.mobile.AudioUtil.noteToFreq(note), duration, volume);
			String path = org.recompile.mobile.AudioUtil.writeCacheFile("./rms/_tone", wav, ".wav");
			if (path == null) { return; }

			org.recompile.mobile.SdlMixerManager.init();
			org.recompile.mobile.SdlMixerManager m = new org.recompile.mobile.SdlMixerManager();
			long handle = m.sdlMixerLoadWav(path);
			if (handle > 0)
			{
				if (lastToneHandle != 0 && lastToneHandle != handle)
				{
					m.sdlMixerStopWav(lastToneHandle);
					m.sdlMixerFreeWav(lastToneHandle);
				}
				lastToneHandle = handle;
				m.sdlMixerPlayWav(handle, 0);
			}
		}
		catch (Throwable t)
		{
			System.out.println("Play Tone failed: " + t.getMessage());
		}
	}

}
