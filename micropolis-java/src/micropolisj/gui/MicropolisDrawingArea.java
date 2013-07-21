// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.gui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.net.URL;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.Timer;

import micropolisj.engine.*;
import static micropolisj.engine.TileConstants.*;
import static micropolisj.gui.ColorParser.parseColor;

public class MicropolisDrawingArea extends JComponent
	implements Scrollable, MapListener
{
	Micropolis m;
	boolean blinkUnpoweredZones = true;
	HashSet<Point> unpoweredZones = new HashSet<Point>();
	boolean blink;
	Timer blinkTimer;
	ToolCursor toolCursor;
	ToolPreview toolPreview;
	int shakeStep;

	static final Dimension PREFERRED_VIEWPORT_SIZE = new Dimension(640,640);
	static final ResourceBundle strings = MainWindow.strings;

	static final int DEFAULT_TILE_SIZE = 16;
	TileImages tileImages;
	int TILE_WIDTH;
	int TILE_HEIGHT;

	public MicropolisDrawingArea(Micropolis engine)
	{
		this.m = engine;
		selectTileSize(DEFAULT_TILE_SIZE);
		m.addMapListener(this);

		addAncestorListener(new AncestorListener() {
		public void ancestorAdded(AncestorEvent evt) {
			startBlinkTimer();
		}
		public void ancestorRemoved(AncestorEvent evt) {
			stopBlinkTimer();
		}
		public void ancestorMoved(AncestorEvent evt) {}
		});
	}

	public void selectTileSize(int newTileSize)
	{
		tileImages = TileImages.getInstance(newTileSize);
		TILE_WIDTH = tileImages.TILE_WIDTH;
		TILE_HEIGHT = tileImages.TILE_HEIGHT;
		revalidate();
	}

	public int getTileSize()
	{
		return TILE_WIDTH;
	}

	public CityLocation getCityLocation(int x, int y)
	{
		return new CityLocation(x / TILE_WIDTH, y / TILE_HEIGHT);
	}

	@Override
	public Dimension getPreferredSize()
	{
		assert this.m != null;

		return new Dimension(TILE_WIDTH*m.getWidth(),TILE_HEIGHT*m.getHeight());
	}

	public void setEngine(Micropolis newEngine)
	{
		assert newEngine != null;

		if (this.m != null) { //old engine
			this.m.removeMapListener(this);
		}
		this.m = newEngine;
		if (this.m != null) { //new engine
			this.m.addMapListener(this);
		}

		// size may have changed
		invalidate();
		repaint();
	}

	void drawSprite(Graphics gr, Sprite sprite)
	{
		assert sprite.isVisible();

		Point p = new Point(
			sprite.x * TILE_WIDTH / 16,
			sprite.y * TILE_HEIGHT / 16
			);

		Image img = tileImages.getSpriteImage(sprite.kind, sprite.frame-1);
		if (img != null) {
			gr.drawImage(img, p.x + sprite.offx, p.y + sprite.offy, null);
		}
		else {
			gr.setColor(Color.RED);
			gr.fillRect(p.x, p.y, 16, 16);
			gr.setColor(Color.WHITE);
			gr.drawString(Integer.toString(sprite.frame-1),p.x,p.y);
		}
	}

	public void paintComponent(Graphics gr)
	{
		final int width = m.getWidth();
		final int height = m.getHeight();

		Rectangle clipRect = gr.getClipBounds();
		int minX = Math.max(0, clipRect.x / TILE_WIDTH);
		int minY = Math.max(0, clipRect.y / TILE_HEIGHT);
		int maxX = Math.min(width, 1 + (clipRect.x + clipRect.width-1) / TILE_WIDTH);
		int maxY = Math.min(height, 1 + (clipRect.y + clipRect.height-1) / TILE_HEIGHT);

		for (int y = minY; y < maxY; y++)
		{
			for (int x = minX; x < maxX; x++)
			{
				int cell = m.getTile(x,y);
				if (blinkUnpoweredZones &&
					isZoneCenter(cell) &&
					(cell & PWRBIT) == 0)
				{
					unpoweredZones.add(new Point(x,y));
					if (blink)
						cell = LIGHTNINGBOLT;
				}

				if (toolPreview != null) {
					int c = toolPreview.getTile(x, y);
					if (c != CLEAR) {
						cell = c;
					}
				}

				gr.drawImage(tileImages.getTileImage(cell),
					x*TILE_WIDTH + (shakeStep != 0 ? getShakeModifier(y) : 0),
					y*TILE_HEIGHT,
					null);
			}
		}

		for (Sprite sprite : m.allSprites())
		{
			if (sprite.isVisible())
			{
				drawSprite(gr, sprite);
			}
		}

		if (toolCursor != null)
		{
			int x0 = toolCursor.rect.x * TILE_WIDTH;
			int x1 = (toolCursor.rect.x + toolCursor.rect.width) * TILE_WIDTH;
			int y0 = toolCursor.rect.y * TILE_HEIGHT;
			int y1 = (toolCursor.rect.y + toolCursor.rect.height) * TILE_HEIGHT;

			gr.setColor(Color.BLACK);
			gr.drawLine(x0-1,y0-1,x0-1,y1-1);
			gr.drawLine(x0-1,y0-1,x1-1,y0-1);
			gr.drawLine(x1+3,y0-3,x1+3,y1+3);
			gr.drawLine(x0-3,y1+3,x1+3,y1+3);

			gr.setColor(Color.WHITE);
			gr.drawLine(x0-4,y0-4,x1+3,y0-4);
			gr.drawLine(x0-4,y0-4,x0-4,y1+3);
			gr.drawLine(x1,  y0-1,x1,  y1  );
			gr.drawLine(x0-1,y1,  x1,  y1  );

			gr.setColor(toolCursor.borderColor);
			gr.drawRect(x0-3,y0-3,x1-x0+5,y1-y0+5);
			gr.drawRect(x0-2,y0-2,x1-x0+3,y1-y0+3);

			if (toolCursor.fillColor != null) {
				gr.setColor(toolCursor.fillColor);
				gr.fillRect(x0,y0,x1-x0,y1-y0);
			}
		}
	}

	static class ToolCursor
	{
		Rectangle rect;
		Color borderColor;
		Color fillColor;
	}

	public void setToolCursor(Rectangle newRect, MicropolisTool tool)
	{
		ToolCursor tp = new ToolCursor();
		tp.rect = newRect;
		tp.borderColor = parseColor(
			strings.getString("tool."+tool.name()+".border")
			);
		tp.fillColor = parseColor(
			strings.getString("tool."+tool.name()+".bgcolor")
			);
		setToolCursor(tp);
	}

	public void setToolCursor(ToolCursor newCursor)
	{
		if (toolCursor == newCursor)
			return;
		if (toolCursor != null && toolCursor.equals(newCursor))
			return;

		if (toolCursor != null)
		{
			repaint(new Rectangle(
				toolCursor.rect.x*TILE_WIDTH - 4,
				toolCursor.rect.y*TILE_HEIGHT - 4,
				toolCursor.rect.width*TILE_WIDTH + 8,
				toolCursor.rect.height*TILE_HEIGHT + 8
				));
		}
		toolCursor = newCursor;
		if (toolCursor != null)
		{
			repaint(new Rectangle(
				toolCursor.rect.x*TILE_WIDTH - 4,
				toolCursor.rect.y*TILE_HEIGHT - 4,
				toolCursor.rect.width*TILE_WIDTH + 8,
				toolCursor.rect.height*TILE_HEIGHT + 8
				));
		}
	}

	public void setToolPreview(ToolPreview newPreview)
	{
		if (toolPreview != null) {
			Rectangle b = toolPreview.getBounds();
			Rectangle r = new Rectangle(
				b.x*TILE_WIDTH,
				b.y*TILE_HEIGHT,
				b.width*TILE_WIDTH,
				b.height*TILE_HEIGHT
				);
			repaint(r);
		}

		toolPreview = newPreview;
		if (toolPreview != null) {

			Rectangle b = toolPreview.getBounds();
			Rectangle r = new Rectangle(
				b.x*TILE_WIDTH,
				b.y*TILE_HEIGHT,
				b.width*TILE_WIDTH,
				b.height*TILE_HEIGHT
				);
			repaint(r);
		}
	}

	//implements Scrollable
	public Dimension getPreferredScrollableViewportSize()
	{
		return PREFERRED_VIEWPORT_SIZE;
	}

	//implements Scrollable
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		if (orientation == SwingConstants.VERTICAL)
			return visibleRect.height;
		else
			return visibleRect.width;
	}

	//implements Scrollable
	public boolean getScrollableTracksViewportWidth()
	{
		return false;
	}

	//implements Scrollable
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}

	//implements Scrollable
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		if (orientation == SwingConstants.VERTICAL)
			return TILE_HEIGHT * 3;
		else
			return TILE_WIDTH * 3;
	}

	private Rectangle getSpriteBounds(Sprite sprite, int x, int y)
	{
		return new Rectangle(
			x*TILE_WIDTH/16+sprite.offx,
			y*TILE_HEIGHT/16+sprite.offy,
			sprite.width, sprite.height);
	}

	public Rectangle getTileBounds(int xpos, int ypos)
	{
		return new Rectangle(xpos*TILE_WIDTH, ypos * TILE_HEIGHT,
			TILE_WIDTH, TILE_HEIGHT);
	}

	//implements MapListener
	public void mapOverlayDataChanged(MapState overlayDataType)
	{
	}

	//implements MapListener
	public void spriteMoved(Sprite sprite)
	{
		repaint(getSpriteBounds(sprite, sprite.lastX, sprite.lastY));
		repaint(getSpriteBounds(sprite, sprite.x, sprite.y));
	}

	//implements MapListener
	public void tileChanged(int xpos, int ypos)
	{
		repaint(getTileBounds(xpos, ypos));
	}

	//implements MapListener
	public void wholeMapChanged()
	{
		repaint();
	}

	void doBlink()
	{
		if (!unpoweredZones.isEmpty())
		{
			blink = !blink;
			for (Point loc : unpoweredZones)
			{
				repaint(getTileBounds(loc.x, loc.y));
			}
			unpoweredZones.clear();
		}
	}

	void startBlinkTimer()
	{
		assert blinkTimer == null;

		ActionListener callback = new ActionListener() {
		public void actionPerformed(ActionEvent evt)
		{
			doBlink();
		}
		};

		blinkTimer = new Timer(500, callback);
		blinkTimer.start();
	}

	void stopBlinkTimer()
	{
		if (blinkTimer != null) {
			blinkTimer.stop();
			blinkTimer = null;
		}
	}

	void shake(int i)
	{
		shakeStep = i;
		repaint();
	}

	static final int SHAKE_STEPS = 40;
	int getShakeModifier(int row)
	{
		return (int)Math.round(4.0 * Math.sin((double)(shakeStep+row/2)/2.0));
	}
}
