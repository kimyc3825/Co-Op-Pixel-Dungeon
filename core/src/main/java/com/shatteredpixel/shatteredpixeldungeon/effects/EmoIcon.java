/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.badlogic.gdx.graphics.Pixmap;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;

public class EmoIcon extends Image {

	protected float maxSize = 2;
	protected float timeScale = 1;

	protected boolean growing	= true;
	
	protected CharSprite owner;
	
	public EmoIcon( CharSprite owner ) {
		super();
		
		this.owner = owner;
		GameScene.add( this );
	}
	
	@Override
	public void update() {
		super.update();

		if (visible) {
			if (growing) {
				scale.set( Math.min(scale.x + Game.elapsed * timeScale, maxSize ));
				if (scale.x >= maxSize) {
					growing = false;
				}
			} else {
				scale.set( Math.max(scale.x - Game.elapsed * timeScale, 1f ));
				if (scale.x <= 1) {
					growing = true;
				}
			}

			if (camera != null) {
				PointF center = centerPoint();
				x = PixelScene.align(camera, owner.x + owner.width() - center.x);
				y = PixelScene.align(camera, owner.y - center.y);
			}
		}
	}

	protected PointF centerPoint(){
		return new PointF(width()/2f, height()/2f);
	};
	
	public static class Sweat extends EmoIcon {

		private static SmartTexture dropTexture;

		public Sweat(CharSprite owner) {
			super(owner);
			texture(drop());
			maxSize = 1.35f;
			timeScale = 2f;
			x = owner.x + owner.width - width / 2f;
			y = owner.y - height / 2f;
		}

		private static SmartTexture drop() {
			if (dropTexture != null && dropTexture.bitmap != null) return dropTexture;
			String[] pixels = {
					"  #  ",
					" #+  ",
					" #+# ",
					"#+..#",
					"#+..#",
					" #.# ",
					"  #  "
			};
			Pixmap bitmap = new Pixmap(pixels[0].length(), pixels.length, Pixmap.Format.RGBA8888);
			for (int y = 0; y < pixels.length; y++) {
				for (int x = 0; x < pixels[y].length(); x++) {
					switch (pixels[y].charAt(x)) {
						case '#': bitmap.drawPixel(x, y, 0x204C64FF); break;
						case '+': bitmap.drawPixel(x, y, 0xC5F5FAFF); break;
						case '.': bitmap.drawPixel(x, y, 0x69C6E3FF); break;
					}
				}
			}
			dropTexture = TextureCache.get(bitmap);
			return dropTexture;
		}
	}

	public static class Sleep extends EmoIcon {
		
		public Sleep( CharSprite owner ) {
			
			super( owner );
			
			copy( Icons.get( Icons.SLEEP ) );
			
			maxSize = 1.2f;
			timeScale = 0.5f;
			
			scale.set( Random.Float( 1, maxSize ) );

			x = owner.x + owner.width - width / 2;
			y = owner.y - height;
		}

		@Override
		protected PointF centerPoint(){
			//centered and significantly up
			return new PointF(width()/2f, 4f+ height()/2f);
		}
	}
	
	public static class Alert extends EmoIcon {
		
		public Alert( CharSprite owner ) {
			
			super( owner );
			
			copy( Icons.get( Icons.ALERT ) );
			
			maxSize = 1.3f;
			timeScale = 2;
			
			scale.set( Random.Float( 1, maxSize ) );

			x = owner.x + owner.width - width / 2;
			y = owner.y - height;
		}

		@Override
		protected PointF centerPoint(){
			//up and left, and centers at the bottom-left
			return new PointF(2.5f + 0.25f*width(), 2.5f + 0.75f*height());
		}
	}

	public static class Investigate extends EmoIcon {

		public Investigate( CharSprite owner ) {

			super( owner );

			copy( Icons.get( Icons.INVESTIGATE ) );

			maxSize = 1.3f;
			timeScale = 1.5f;

			scale.set( Random.Float( 1, maxSize ) );

			x = owner.x + owner.width - width / 2;
			y = owner.y - height;
		}

		@Override
		protected PointF centerPoint(){
			//up and left, and centers at the bottom-left
			return new PointF(2.5f + 0.25f*width(), 2.5f + 0.75f*height());
		}
	}
	
	public static class Lost extends EmoIcon {
		
		public Lost( CharSprite owner ){
			super( owner );
			
			copy( Icons.get( Icons.LOST ) );
			
			maxSize = 1.25f;
			timeScale = 1;
			
			scale.set( Random.Float( 1, maxSize ) );
			
			x = owner.x + owner.width - width / 2;
			y = owner.y - height;
		}

		@Override
		protected PointF centerPoint(){
			//up and left, and centers at the bottom-left
			return new PointF(2.5f + 0.25f*width(), 2.5f + 0.75f*height());
		}
	}

}
