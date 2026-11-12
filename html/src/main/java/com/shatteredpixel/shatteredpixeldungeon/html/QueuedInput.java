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

package com.shatteredpixel.shatteredpixeldungeon.html;

import com.badlogic.gdx.InputProcessor;

import java.util.ArrayList;

//records input events coming from DOM callbacks and replays them, in order, on the game thread
public class QueuedInput implements InputProcessor {

	private interface Event {
		void send( InputProcessor p );
	}

	private ArrayList<Event> events = new ArrayList<>();
	private ArrayList<Event> draining = new ArrayList<>();

	public int received, delivered;

	public void drain( InputProcessor target ){
		if (events.isEmpty()) return;
		delivered += events.size();
		ArrayList<Event> list = events;
		events = draining;
		draining = list;
		for (Event e : list){
			e.send(target);
		}
		list.clear();
	}

	private void add( Event e ){
		received++;
		events.add(e);
	}

	@Override
	public boolean keyDown(int keycode) {
		add(p -> p.keyDown(keycode));
		return true;
	}

	@Override
	public boolean keyUp(int keycode) {
		add(p -> p.keyUp(keycode));
		return true;
	}

	@Override
	public boolean keyTyped(char character) {
		add(p -> p.keyTyped(character));
		return true;
	}

	@Override
	public boolean touchDown(int screenX, int screenY, int pointer, int button) {
		add(p -> p.touchDown(screenX, screenY, pointer, button));
		return true;
	}

	@Override
	public boolean touchUp(int screenX, int screenY, int pointer, int button) {
		add(p -> p.touchUp(screenX, screenY, pointer, button));
		return true;
	}

	@Override
	public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
		add(p -> p.touchCancelled(screenX, screenY, pointer, button));
		return true;
	}

	@Override
	public boolean touchDragged(int screenX, int screenY, int pointer) {
		add(p -> p.touchDragged(screenX, screenY, pointer));
		return true;
	}

	@Override
	public boolean mouseMoved(int screenX, int screenY) {
		add(p -> p.mouseMoved(screenX, screenY));
		return true;
	}

	@Override
	public boolean scrolled(float amountX, float amountY) {
		add(p -> p.scrolled(amountX, amountY));
		return true;
	}
}
