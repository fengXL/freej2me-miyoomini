/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
*/
package org.recompile.freej2me;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * 系统菜单：在游戏画面上叠加的半透明菜单。
 *
 * 渲染在 Java 端完成（复用 font.ttf），不依赖 SDL2_ttf。
 * 菜单打开时按键由 Anbu 拦截，不再转发给游戏。
 */
public class SystemMenu
{
	// 菜单项
	private static final int ITEM_EXIT    = 0;
	private static final int ITEM_PHONE   = 1;
	private static final int ITEM_DPAD    = 2;
	private static final int ITEM_VOLUME  = 3;
	private static final int ITEM_FPS     = 4;
	private static final int ITEM_COUNT   = 5;

	private static final String[] PHONE_NAMES = { "标准手机(P)", "诺基亚(N)", "扩展诺基亚(E)", "西门子(S)", "摩托罗拉(M)" };
	private static final String[] PHONE_KEYS  = { "p", "n", "e", "s", "m" };

	public boolean visible = false;
	public int selection = 0;

	private final SDLConfig config;
	private final Listener listener;

	/** 设置变化时的回调（由 Anbu 实现：应用键位/FPS等） */
	public interface Listener
	{
		void onSettingChanged();
		void onExitGame();
		void requestRepaint();
	}

	public SystemMenu(SDLConfig config, Listener listener)
	{
		this.config = config;
		this.listener = listener;
	}

	public void setVisible(boolean v)
	{
		visible = v;
		System.out.println("系统菜单: " + (v ? "打开" : "关闭"));
		if (listener != null) listener.requestRepaint();
	}

	public void toggle()
	{
		setVisible(!visible);
	}

	/**
	 * 处理原始SDL键码（菜单打开时由 Anbu 调用，不再转发给游戏）。
	 * @return true 表示按键已被菜单消费
	 */
	public boolean handleKey(int keycode, boolean pressed)
	{
		if (!visible) return false;
		if (!pressed) return true; // 释放事件也一并吞掉

		switch (keycode)
		{
			case 0x40000052: // SDLK_UP
				selection = (selection + ITEM_COUNT - 1) % ITEM_COUNT;
				break;

			case 0x40000051: // SDLK_DOWN
				selection = (selection + 1) % ITEM_COUNT;
				break;

			case 0x40000050: // SDLK_LEFT
				adjust(-1);
				break;

			case 0x4000004F: // SDLK_RIGHT
				adjust(1);
				break;

			case 0x0D: // SDLK_RETURN 确认
			case 0x6F: // 右摇杆按下同样作为OK
				activate();
				break;

			case 0x1B:      // SDLK_ESC：关闭菜单
			case 0x4000004A: // SDLK_HOME：关闭菜单
				setVisible(false);
				break;

			default:
				return true; // 菜单打开期间吞掉所有其它按键
		}
		if (listener != null) listener.requestRepaint();
		return true;
	}

	private void adjust(int dir)
	{
		switch (selection)
		{
			case ITEM_PHONE:
				cyclePhone(dir);
				break;

			case ITEM_DPAD:
				toggleDpad();
				break;

			case ITEM_VOLUME:
				adjustVolume(dir * 20);
				break;

			case ITEM_FPS:
				adjustFps(dir);
				break;

			case ITEM_EXIT:
				break;
		}
	}

	private void activate()
	{
		if (selection == ITEM_EXIT)
		{
			if (listener != null) listener.onExitGame();
			return;
		}
		adjust(1);
	}

	private void cyclePhone(int dir)
	{
		int idx = phoneIndex();
		idx = (idx + dir + PHONE_KEYS.length) % PHONE_KEYS.length;
		config.settings.put("phone", PHONE_KEYS[idx]);
		saveAndNotify();
	}

	private void toggleDpad()
	{
		String cur = config.settings.get("dpad");
		if (cur == null || cur.equals("nav")) config.settings.put("dpad", "num");
		else config.settings.put("dpad", "nav");
		saveAndNotify();
	}

	private void adjustVolume(int delta)
	{
		int vol = volume();
		vol += delta;
		if (vol < 0) vol = 0;
		if (vol > 100) vol = 100;
		config.settings.put("sound", String.valueOf(vol));
		org.recompile.mobile.SdlMixerManager.setMasterVolume(vol);
		saveAndNotify();
	}

	private void adjustFps(int dir)
	{
		int fps = fpsValue();
		if (dir > 0) { if (fps < 60) fps = 60; }
		else { if (fps > 30) fps = 30; }
		config.settings.put("fps", String.valueOf(fps));
		saveAndNotify();
	}

	private void saveAndNotify()
	{
		config.saveConfig();
		if (listener != null) listener.onSettingChanged();
	}

	private int phoneIndex()
	{
		String p = config.settings.get("phone");
		if (p == null) return 0;
		for (int i = 0; i < PHONE_KEYS.length; i++)
		{
			if (PHONE_KEYS[i].equals(p)) return i;
		}
		return 0;
	}

	private int volume()
	{
		try { return Integer.parseInt(config.settings.get("sound")); }
		catch (Exception e) { return 100; }
	}

	private int fpsValue()
	{
		try { return Integer.parseInt(config.settings.get("fps")); }
		catch (Exception e) { return 60; }
	}

	private boolean dpadNav()
	{
		String d = config.settings.get("dpad");
		return d == null || d.equals("nav");
	}

	/**
	 * 在 LCD 帧上绘制菜单
	 */
	public void draw(Graphics2D g, int lcdWidth, int lcdHeight)
	{
		if (!visible) return;

		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		int pad = 12;
		int lineH = 26;
		int titleH = 34;
		int footerH = 24;
		int panelW = Math.min(lcdWidth - 16, 260);
		int panelH = titleH + ITEM_COUNT * lineH + footerH + pad;
		int x = (lcdWidth - panelW) / 2;
		int y = Math.max(8, (lcdHeight - panelH) / 3);

		// 半透明背景面板
		g.setComposite(AlphaComposite.SrcOver.derive(0.82f));
		g.setColor(new Color(16, 18, 24));
		g.fill(new RoundRectangle2D.Float(x, y, panelW, panelH, 10, 10));
		g.setColor(new Color(70, 130, 200));
		g.draw(new RoundRectangle2D.Float(x, y, panelW, panelH, 10, 10));
		g.setComposite(AlphaComposite.SrcOver);

		Font base = Anbu.getFont();
		Font titleFont = base.deriveFont(Font.BOLD, 16f);
		Font itemFont = base.deriveFont(Font.PLAIN, 14f);

		// 标题
		g.setColor(new Color(120, 180, 255));
		g.setFont(titleFont);
		g.drawString("系统菜单", x + pad, y + 24);

		String[] values = new String[ITEM_COUNT];
		values[ITEM_EXIT]   = "";
		values[ITEM_PHONE]  = PHONE_NAMES[phoneIndex()] + " ›";
		values[ITEM_DPAD]   = (dpadNav() ? "导航键 ↑↓←→" : "数字键 2468") + " ›";
		values[ITEM_VOLUME] = "‹ " + volume() + " ›";
		values[ITEM_FPS]    = (fpsValue() >= 60 ? "60" : "30") + " ›";

		String[] names = new String[ITEM_COUNT];
		names[ITEM_EXIT]   = "退出游戏";
		names[ITEM_PHONE]  = "按键模式";
		names[ITEM_DPAD]   = "十字键映射";
		names[ITEM_VOLUME] = "音量";
		names[ITEM_FPS]    = "帧率";

		int iy = y + titleH;
		for (int i = 0; i < ITEM_COUNT; i++)
		{
			if (i == selection)
			{
				g.setColor(new Color(45, 90, 160));
				g.fillRoundRect(x + 6, iy - 2, panelW - 12, lineH - 4, 6, 6);
			}
			g.setColor(i == selection ? Color.WHITE : new Color(190, 195, 205));
			g.setFont(itemFont);
			g.drawString(names[i], x + pad, iy + 16);
			g.drawString(values[i], x + panelW - pad - g.getFontMetrics().stringWidth(values[i]), iy + 16);
			iy += lineH;
		}

		// 底部提示
		g.setColor(new Color(130, 135, 145));
		g.setFont(base.deriveFont(Font.PLAIN, 12f));
		g.drawString("上下选择 · 左右调节 · MENU关闭", x + pad, iy + 14);
	}
}
